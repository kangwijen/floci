package tests

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"floci-sdk-test-go/internal/testutil"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/servicediscovery"
	sdtypes "github.com/aws/aws-sdk-go-v2/service/servicediscovery/types"
	"github.com/aws/smithy-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const cloudMapInstanceID = "i-cm-go-1"

func uniqueCloudMapName(prefix string) string {
	return fmt.Sprintf("%s-%x", prefix, time.Now().UnixNano()&0xffffffff)
}

func isServiceDiscoveryDisabled(err error) bool {
	if strings.Contains(strings.ToLower(err.Error()), "not enabled") {
		return true
	}
	var apiErr smithy.APIError
	if errors.As(err, &apiErr) {
		return apiErr.ErrorCode() == "ServiceUnavailableException"
	}
	return false
}

func awaitServiceDiscoverySuccess(ctx context.Context, sd *servicediscovery.Client, operationID string) error {
	for i := 0; i < 20; i++ {
		op, err := sd.GetOperation(ctx, &servicediscovery.GetOperationInput{
			OperationId: aws.String(operationID),
		})
		if err != nil {
			return err
		}
		switch op.Operation.Status {
		case sdtypes.OperationStatusSuccess:
			return nil
		case sdtypes.OperationStatusFail:
			msg := aws.ToString(op.Operation.ErrorMessage)
			return fmt.Errorf("operation %s failed: %s", operationID, msg)
		default:
			time.Sleep(100 * time.Millisecond)
		}
	}
	return fmt.Errorf("operation %s did not reach SUCCESS", operationID)
}

func TestCloudMapNamespaceServiceInstanceFlow(t *testing.T) {
	ctx := context.Background()
	sd := testutil.ServiceDiscoveryClient()
	namespaceName := uniqueCloudMapName("cm-ns")
	serviceName := uniqueCloudMapName("cm-svc")

	createNs, err := sd.CreateHttpNamespace(ctx, &servicediscovery.CreateHttpNamespaceInput{
		Name:        aws.String(namespaceName),
		Description: aws.String("floci sdk compat"),
	})
	if err != nil {
		if isServiceDiscoveryDisabled(err) {
			t.Skipf("Cloud Map (servicediscovery) is not enabled: %v", err)
		}
		require.NoError(t, err)
	}
	require.NoError(t, awaitServiceDiscoverySuccess(ctx, sd, aws.ToString(createNs.OperationId)))

	listed, err := sd.ListNamespaces(ctx, &servicediscovery.ListNamespacesInput{})
	require.NoError(t, err)

	var namespaceID string
	for _, ns := range listed.Namespaces {
		if aws.ToString(ns.Name) == namespaceName {
			namespaceID = aws.ToString(ns.Id)
			assert.True(t, strings.HasPrefix(namespaceID, "ns-"))
			assert.Equal(t, sdtypes.NamespaceTypeHttp, ns.Type)
			assert.Contains(t, aws.ToString(ns.Arn), "servicediscovery")
			break
		}
	}
	require.NotEmpty(t, namespaceID)

	getNs, err := sd.GetNamespace(ctx, &servicediscovery.GetNamespaceInput{Id: aws.String(namespaceID)})
	require.NoError(t, err)
	assert.Equal(t, namespaceName, aws.ToString(getNs.Namespace.Name))

	createSvc, err := sd.CreateService(ctx, &servicediscovery.CreateServiceInput{
		Name:        aws.String(serviceName),
		NamespaceId: aws.String(namespaceID),
		Type:        sdtypes.ServiceTypeOptionHttp,
	})
	require.NoError(t, err)
	serviceID := aws.ToString(createSvc.Service.Id)
	assert.True(t, strings.HasPrefix(serviceID, "srv-"))
	assert.Equal(t, serviceName, aws.ToString(createSvc.Service.Name))

	t.Cleanup(func() {
		_, _ = sd.DeregisterInstance(ctx, &servicediscovery.DeregisterInstanceInput{
			ServiceId:  aws.String(serviceID),
			InstanceId: aws.String(cloudMapInstanceID),
		})
		_, _ = sd.DeleteService(ctx, &servicediscovery.DeleteServiceInput{Id: aws.String(serviceID)})
		_, _ = sd.DeleteNamespace(ctx, &servicediscovery.DeleteNamespaceInput{Id: aws.String(namespaceID)})
	})

	reg, err := sd.RegisterInstance(ctx, &servicediscovery.RegisterInstanceInput{
		ServiceId:  aws.String(serviceID),
		InstanceId: aws.String(cloudMapInstanceID),
		Attributes: map[string]string{
			"AWS_INSTANCE_IPV4": "10.0.0.7",
			"AWS_INSTANCE_PORT": "9090",
		},
	})
	require.NoError(t, err)
	require.NoError(t, awaitServiceDiscoverySuccess(ctx, sd, aws.ToString(reg.OperationId)))

	instances, err := sd.ListInstances(ctx, &servicediscovery.ListInstancesInput{
		ServiceId: aws.String(serviceID),
	})
	require.NoError(t, err)
	require.Len(t, instances.Instances, 1)
	assert.Equal(t, cloudMapInstanceID, aws.ToString(instances.Instances[0].Id))
	assert.Equal(t, "10.0.0.7", instances.Instances[0].Attributes["AWS_INSTANCE_IPV4"])
}
