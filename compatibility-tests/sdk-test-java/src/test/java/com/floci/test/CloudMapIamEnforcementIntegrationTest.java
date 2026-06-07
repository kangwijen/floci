package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.ServiceDiscoveryException;
import software.amazon.awssdk.services.sts.StsClient;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end IAM enforcement tests for Cloud Map ({@code servicediscovery}).
 *
 * <p>Skips when {@link TestFixtures#isIamEnforcementEnabled()} is {@code false}.
 * Requires admin credentials via {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}.
 */
@DisplayName("Cloud Map IAM Enforcement")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudMapIamEnforcementIntegrationTest {

    private static final String USER = "cloudmap-iam-enf-user";

    private static IamClient iam;
    private static String userAccessKeyId;
    private static String userSecretKey;
    private static String accountId;
    private static final String REGION = "us-east-1";

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(TestFixtures.isIamEnforcementEnabled(),
                "IAM enforcement is not enabled — set floci.services.iam.enforcement-enabled=true to run these tests");

        iam = TestFixtures.iamClient();
        try (StsClient sts = TestFixtures.stsClient()) {
            accountId = sts.getCallerIdentity().account();
        }

        iam.createUser(CreateUserRequest.builder().userName(USER).build());
        CreateAccessKeyResponse keyResp = iam.createAccessKey(
                CreateAccessKeyRequest.builder().userName(USER).build());
        userAccessKeyId = keyResp.accessKey().accessKeyId();
        userSecretKey = keyResp.accessKey().secretAccessKey();
    }

    @AfterAll
    static void cleanup() {
        if (iam == null) {
            return;
        }
        try {
            iam.deleteUserPolicy(DeleteUserPolicyRequest.builder()
                    .userName(USER).policyName("inline-cloudmap-allow").build());
        } catch (Exception ignored) {
        }
        try {
            iam.deleteAccessKey(DeleteAccessKeyRequest.builder()
                    .userName(USER).accessKeyId(userAccessKeyId).build());
        } catch (Exception ignored) {
        }
        try {
            iam.deleteUser(DeleteUserRequest.builder().userName(USER).build());
        } catch (Exception ignored) {
        }
        iam.close();
    }

    private static ServiceDiscoveryClient sdWithUserCredentials() {
        return ServiceDiscoveryClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(userAccessKeyId, userSecretKey)))
                .overrideConfiguration(o -> o.putAdvancedOption(
                        SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, Boolean.TRUE))
                .build();
    }

    private static String allowCreateHttpNamespacePolicy(String resource) {
        return """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"servicediscovery:CreateHttpNamespace","Resource":"%s"}
            ]}""".formatted(resource);
    }

    @Test
    @Order(1)
    @DisplayName("CreateHttpNamespace denied without policy")
    void createHttpNamespaceDeniedWithoutPolicy() {
        String namespaceName = TestFixtures.uniqueName("cm-iam-deny");

        try (ServiceDiscoveryClient sd = sdWithUserCredentials()) {
            assertThatThrownBy(() -> sd.createHttpNamespace(r -> r.name(namespaceName)))
                    .isInstanceOf(ServiceDiscoveryException.class)
                    .extracting(e -> ((ServiceDiscoveryException) e).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @Order(2)
    @DisplayName("CreateHttpNamespace allowed with namespace/* resource")
    void createHttpNamespaceAllowedWithNamespaceWildcard() {
        String namespaceName = TestFixtures.uniqueName("cm-iam-ns");
        String resource = "arn:aws:servicediscovery:" + REGION + ":" + accountId + ":namespace/*";

        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(USER)
                .policyName("inline-cloudmap-allow")
                .policyDocument(allowCreateHttpNamespacePolicy(resource))
                .build());

        try (ServiceDiscoveryClient sd = sdWithUserCredentials()) {
            assertThatCode(() -> sd.createHttpNamespace(r -> r.name(namespaceName)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @Order(3)
    @DisplayName("CreateHttpNamespace allowed with * resource")
    void createHttpNamespaceAllowedWithStarResource() {
        String namespaceName = TestFixtures.uniqueName("cm-iam-star");

        iam.putUserPolicy(PutUserPolicyRequest.builder()
                .userName(USER)
                .policyName("inline-cloudmap-allow")
                .policyDocument(allowCreateHttpNamespacePolicy("*"))
                .build());

        try (ServiceDiscoveryClient sd = sdWithUserCredentials()) {
            assertThatCode(() -> sd.createHttpNamespace(r -> r.name(namespaceName)))
                    .doesNotThrowAnyException();
        }
    }
}
