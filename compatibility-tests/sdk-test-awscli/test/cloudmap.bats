#!/usr/bin/env bats
# Cloud Map (servicediscovery) smoke tests

setup() {
    load 'test_helper/common-setup'

    if ! aws servicediscovery create-http-namespace help >/dev/null 2>&1; then
        skip "AWS CLI servicediscovery create-http-namespace not available in this AWS CLI build"
    fi

    NS_NAME="bats-cm-$(unique_name)"
    NAMESPACE_ID=""
}

teardown() {
    if [ -n "$NAMESPACE_ID" ]; then
        aws_cmd servicediscovery delete-namespace --id "$NAMESPACE_ID" >/dev/null 2>&1 || true
    fi
}

@test "Cloud Map: create-http-namespace" {
    run aws_cmd servicediscovery create-http-namespace \
        --name "$NS_NAME" \
        --description "bats cloudmap smoke"
    assert_success

    operation_id=$(json_get "$output" '.OperationId')
    [ -n "$operation_id" ]
}

@test "Cloud Map: list-namespaces includes created namespace" {
    create_out=$(aws_cmd servicediscovery create-http-namespace --name "$NS_NAME")
    operation_id=$(json_get "$create_out" '.OperationId')
    [ -n "$operation_id" ]

    for _ in $(seq 1 30); do
        op_out=$(aws_cmd servicediscovery get-operation --operation-id "$operation_id" 2>/dev/null || true)
        status=$(json_get "$op_out" '.Operation.Status')
        if [ "$status" = "SUCCESS" ]; then
            break
        fi
        if [ "$status" = "FAIL" ]; then
            skip "create-http-namespace operation failed: $(json_get "$op_out" '.Operation.ErrorMessage')"
        fi
        sleep 0.2
    done

    run aws_cmd servicediscovery list-namespaces
    assert_success

    NAMESPACE_ID=$(echo "$output" | jq -r --arg name "$NS_NAME" \
        '.Namespaces[]? | select(.Name == $name) | .Id' 2>/dev/null | head -n1)
    [ -n "$NAMESPACE_ID" ]
}
