"""Cloud Map (servicediscovery) compatibility tests."""

import time

import pytest
from botocore.exceptions import ClientError

INSTANCE_ID = "i-cm-py-1"


def _skip_if_disabled(exc: ClientError) -> None:
    code = exc.response["Error"]["Code"]
    msg = exc.response["Error"].get("Message", "")
    if code == "ServiceUnavailableException" or "not enabled" in msg.lower():
        pytest.skip(f"Cloud Map (servicediscovery) is not enabled: {msg}")


def _await_success(client, operation_id: str) -> None:
    for _ in range(20):
        op = client.get_operation(OperationId=operation_id)
        status = op["Operation"]["Status"]
        if status == "SUCCESS":
            return
        if status == "FAIL":
            pytest.fail(
                f"Operation {operation_id} failed: {op['Operation'].get('ErrorMessage')}"
            )
        time.sleep(0.1)
    pytest.fail(f"Operation {operation_id} did not reach SUCCESS")


def test_cloudmap_namespace_service_instance_flow(servicediscovery_client, unique_name):
    """Create namespace, service, and register an instance (Phase 1 flow)."""
    namespace_name = f"cm-ns-{unique_name}"
    service_name = f"cm-svc-{unique_name}"

    try:
        create_ns = servicediscovery_client.create_http_namespace(
            Name=namespace_name, Description="floci sdk compat"
        )
    except ClientError as exc:
        _skip_if_disabled(exc)
        raise

    _await_success(servicediscovery_client, create_ns["OperationId"])

    listed = servicediscovery_client.list_namespaces()
    namespace = next(
        n for n in listed["Namespaces"] if n["Name"] == namespace_name
    )
    namespace_id = namespace["Id"]
    assert namespace_id.startswith("ns-")
    assert namespace["Type"] == "HTTP"
    assert "servicediscovery" in namespace["Arn"]

    get_ns = servicediscovery_client.get_namespace(Id=namespace_id)
    assert get_ns["Namespace"]["Name"] == namespace_name

    create_svc = servicediscovery_client.create_service(
        Name=service_name, NamespaceId=namespace_id, Type="HTTP"
    )
    service_id = create_svc["Service"]["Id"]
    assert service_id.startswith("srv-")
    assert create_svc["Service"]["Name"] == service_name

    reg = servicediscovery_client.register_instance(
        ServiceId=service_id,
        InstanceId=INSTANCE_ID,
        Attributes={
            "AWS_INSTANCE_IPV4": "10.0.0.7",
            "AWS_INSTANCE_PORT": "9090",
        },
    )
    _await_success(servicediscovery_client, reg["OperationId"])

    instances = servicediscovery_client.list_instances(ServiceId=service_id)
    assert len(instances["Instances"]) == 1
    assert instances["Instances"][0]["Id"] == INSTANCE_ID
    assert instances["Instances"][0]["Attributes"]["AWS_INSTANCE_IPV4"] == "10.0.0.7"

    try:
        dereg = servicediscovery_client.deregister_instance(
            ServiceId=service_id, InstanceId=INSTANCE_ID
        )
        _await_success(servicediscovery_client, dereg["OperationId"])
        servicediscovery_client.delete_service(Id=service_id)
        servicediscovery_client.delete_namespace(Id=namespace_id)
    except ClientError:
        pass
