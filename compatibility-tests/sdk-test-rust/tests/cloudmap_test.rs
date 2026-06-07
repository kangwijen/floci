mod common;

use aws_sdk_servicediscovery::types::{NamespaceType, OperationStatus, ServiceTypeOption};
use std::collections::HashMap;
use std::time::Duration;

const INSTANCE_ID: &str = "i-cm-rust-1";

fn is_service_disabled(err: impl std::fmt::Display) -> bool {
    let msg = err.to_string().to_lowercase();
    msg.contains("not enabled") || msg.contains("serviceunavailableexception")
}

async fn await_success(
    client: &aws_sdk_servicediscovery::Client,
    operation_id: &str,
) -> Result<(), String> {
    for _ in 0..20 {
        let op = client
            .get_operation()
            .operation_id(operation_id)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        match op.operation().and_then(|o| o.status()) {
            Some(OperationStatus::Success) => return Ok(()),
            Some(OperationStatus::Fail) => {
                let msg = op
                    .operation()
                    .and_then(|o| o.error_message())
                    .unwrap_or("unknown");
                return Err(format!("operation {operation_id} failed: {msg}"));
            }
            _ => tokio::time::sleep(Duration::from_millis(100)).await,
        }
    }
    Err(format!("operation {operation_id} did not reach SUCCESS"))
}

#[tokio::test(flavor = "multi_thread")]
async fn test_cloudmap_namespace_service_instance_flow() {
    let sd = common::servicediscovery_client().await;
    let namespace_name = format!("cm-ns-{}", uuid_simple());
    let service_name = format!("cm-svc-{}", uuid_simple());

    let create_ns = match sd
        .create_http_namespace()
        .name(&namespace_name)
        .description("floci sdk compat")
        .send()
        .await
    {
        Ok(resp) => resp,
        Err(err) => {
            if is_service_disabled(&err) {
                eprintln!("Cloud Map (servicediscovery) is not enabled — skipping");
                return;
            }
            panic!("CreateHttpNamespace failed: {err}");
        }
    };

    let op_id = create_ns.operation_id().expect("operation id");
    await_success(&sd, op_id)
        .await
        .expect("create namespace operation");

    let listed = sd.list_namespaces().send().await.expect("list namespaces");
    let namespace = listed
        .namespaces()
        .iter()
        .find(|n| n.name() == Some(namespace_name.as_str()))
        .expect("namespace in list");
    let namespace_id = namespace.id().expect("namespace id");
    assert!(namespace_id.starts_with("ns-"));
    assert_eq!(namespace.r#type(), Some(&NamespaceType::Http));
    assert!(namespace.arn().unwrap_or("").contains("servicediscovery"));

    let get_ns = sd
        .get_namespace()
        .id(namespace_id)
        .send()
        .await
        .expect("get namespace");
    assert_eq!(get_ns.namespace().and_then(|n| n.name()), Some(namespace_name.as_str()));

    let create_svc = sd
        .create_service()
        .name(&service_name)
        .namespace_id(namespace_id)
        .r#type(ServiceTypeOption::Http)
        .send()
        .await
        .expect("create service");
    let service_id = create_svc.service().and_then(|s| s.id()).expect("service id");
    assert!(service_id.starts_with("srv-"));

    let _guard = common::CleanupGuard::new({
        let sd = sd.clone();
        let namespace_id = namespace_id.to_string();
        let service_id = service_id.to_string();
        async move {
            let _ = sd
                .deregister_instance()
                .service_id(&service_id)
                .instance_id(INSTANCE_ID)
                .send()
                .await;
            let _ = sd.delete_service().id(&service_id).send().await;
            let _ = sd.delete_namespace().id(&namespace_id).send().await;
        }
    });

    let mut attrs = HashMap::new();
    attrs.insert("AWS_INSTANCE_IPV4".into(), "10.0.0.7".into());
    attrs.insert("AWS_INSTANCE_PORT".into(), "9090".into());

    let reg = sd
        .register_instance()
        .service_id(service_id)
        .instance_id(INSTANCE_ID)
        .set_attributes(Some(attrs))
        .send()
        .await
        .expect("register instance");
    await_success(&sd, reg.operation_id().expect("register op id"))
        .await
        .expect("register operation");

    let instances = sd
        .list_instances()
        .service_id(service_id)
        .send()
        .await
        .expect("list instances");
    assert_eq!(instances.instances().len(), 1);
    let inst = &instances.instances()[0];
    assert_eq!(inst.id(), Some(INSTANCE_ID));
    assert_eq!(inst.attributes().get("AWS_INSTANCE_IPV4").map(String::as_str), Some("10.0.0.7"));
}

fn uuid_simple() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:08x}", n ^ std::process::id() as u64)
}
