#!/usr/bin/env bash
# Common setup for bats tests

# Load bats-support and bats-assert
# Supports both local lib/ directory and BATS_LIB_PATH for Docker
_COMMON_SETUP_DIR="${BATS_TEST_DIRNAME}"
if [[ -d "${_COMMON_SETUP_DIR}/../../lib/bats-support" ]]; then
    load "${_COMMON_SETUP_DIR}/../../lib/bats-support/load.bash"
    load "${_COMMON_SETUP_DIR}/../../lib/bats-assert/load.bash"
elif [[ -n "${BATS_LIB_PATH}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load.bash"
    load "${BATS_LIB_PATH}/bats-assert/load.bash"
else
    echo "Error: Cannot find bats-support/bats-assert libraries" >&2
    exit 1
fi

# Shared endpoint/credential exports (permissive test/test vs CTF IAM keys).
_AWSCLI_MODULE_DIR="$(cd "${_COMMON_SETUP_DIR}/.." && pwd)"
if [[ -f "${_AWSCLI_MODULE_DIR}/../lib/ctf-env.sh" ]]; then
    # shellcheck source=../../lib/ctf-env.sh
    source "${_AWSCLI_MODULE_DIR}/../lib/ctf-env.sh"
elif [[ -f /opt/floci/ctf-env.sh ]]; then
    # shellcheck source=/opt/floci/ctf-env.sh
    source /opt/floci/ctf-env.sh
else
    echo "Error: lib/ctf-env.sh not found" >&2
    exit 1
fi

# Helper function to run AWS CLI commands with endpoint
aws_cmd() {
    aws --endpoint-url "$FLOCI_ENDPOINT" --region "$AWS_DEFAULT_REGION" --output json "$@" 2>&1
}

# Generate unique name for test resources
unique_name() {
    local prefix="${1:-test}"
    echo "${prefix}-$(date +%s)-$$"
}

# Wait for DynamoDB table to exist
ddb_wait_table() {
    local table_name="$1"
    aws_cmd dynamodb wait table-exists --table-name "$table_name" >/dev/null 2>&1 || true
}

# Extract JSON value using jq
json_get() {
    local json="$1"
    local path="$2"
    echo "$json" | jq -r "$path" 2>/dev/null || echo ""
}

# Check if operation is unsupported
is_unsupported_operation() {
    local output="$1"
    [[ "$output" == *"(UnsupportedOperation)"* ]] || [[ "$output" == *" is not supported."* ]]
}
