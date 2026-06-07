# floci-compatibility-tests

Compatibility test suite for [floci-ctf](https://github.com/kangwijen/floci-ctf) — a security-hardened local AWS emulator fork (upstream **1.5.23**).

Verifies that standard AWS tooling (SDKs, CDK, OpenTofu/Terraform) works correctly against the emulator without modification. Tests run against a live Floci instance and use real AWS SDK clients — no mocks.

## Upstream 1.5.23 highlights

Merged from [floci-io/floci](https://github.com/floci-io/floci) tag **1.5.23**:

| Area | Change |
| --- | --- |
| Cloud Map | New `servicediscovery` management API and docs |
| AppSync | Phase 2: schema registry, AWS scalars, CRUDL completion |
| IAM | Standard EKS cluster and node group managed policies seeded |
| DynamoDB | Reserved keyword updates; SSE specification persistence |
| Glue | Table version checks enforced |
| Step Functions | `Catch` honored on Lambda task failures |
| EC2 | `AttachTime` in `describe-instances` network interface response |

`sdk-test-java` covers **AppSync** (`AppSyncTest`) and **Cloud Map** (`CloudMapTest`). Use `just test-cloudmap-java` for a focused Cloud Map run.

## CTF fork (floci-ctf)

The main **floci-ctf** repository enables IAM enforcement, strict mode, and SigV4 validation in Compose. Most modules assume permissive `test`/`test` credentials in `.env`; against the hardened image you must:

1. Set `FLOCI_CTF_PROFILE=ctf` in `.env` (see `env.example`).
2. Export operator `FLOCI_AUTH_ROOT_*` (or participant IAM keys from `CreateAccessKey`).
3. Set `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` to registered credentials.
4. Set `FLOCI_IAM_ENFORCEMENT=true` when targeting an enforcement-enabled instance (CTF tests probe at runtime, but this documents intent for CI and helpers).
5. Expect unsigned or `test`/`test` calls to return `403`.

Shared helper: [`lib/ctf-env.sh`](lib/ctf-env.sh) sources profile-aware defaults for bash recipes and IaC test helpers.

### CTF-focused Java tests

`sdk-test-java` includes enforcement probes that **skip** when enforcement is off:

| Test class | Purpose |
| --- | --- |
| `IamEnforcementTest` | Allow/deny IAM policy scenarios when `floci.services.iam.enforcement-enabled=true` |
| `CloudMapIamEnforcementIntegrationTest` | Cloud Map API behaviour under IAM enforcement |
| `AppSyncIamEnforcementIntegrationTest` | AppSync CreateGraphqlApi under IAM enforcement |

Run against a CTF Compose instance:

```bash
cp env.example .env
# edit .env: FLOCI_CTF_PROFILE=ctf, FLOCI_IAM_ENFORCEMENT=true, FLOCI_AUTH_ROOT_*, AWS_*

just test-ctf-java
```

Or manually:

```bash
export FLOCI_ENDPOINT=http://localhost:4566
export FLOCI_CTF_PROFILE=ctf
export FLOCI_IAM_ENFORCEMENT=true
export AWS_ACCESS_KEY_ID="$FLOCI_AUTH_ROOT_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$FLOCI_AUTH_ROOT_SECRET_ACCESS_KEY"
cd sdk-test-java && mvn test -Dtest=IamEnforcementTest,CloudMapIamEnforcementIntegrationTest
```

S3 presign tests use the AWS SDK presigner and work when the signing access key is registered in IAM (or matches the operator root pair).

## Quick Start

```bash
# Install just (task runner)
# macOS: brew install just
# Linux: cargo install just

# Copy and configure environment
cp env.example .env

# Install dependencies
just setup

# Run all SDK tests (permissive profile)
just test-all

# Run specific SDK tests
just test-python
just test-typescript
just test-awscli

# CTF enforcement probes (requires floci-ctf + ctf profile in .env)
just test-ctf-java

# IaC compatibility (CDK, Terraform, OpenTofu)
just test-compat
```

## Test matrix

| Module | Language / tool | Command | CTF enforcement |
| --- | --- | --- | --- |
| [`sdk-test-python`](sdk-test-python/) | Python 3 / pytest | `just test-python` | permissive (`test`/`test`) |
| [`sdk-test-node`](sdk-test-node/) | TypeScript / vitest | `just test-typescript` | permissive |
| [`sdk-test-awscli`](sdk-test-awscli/) | Bash / bats-core | `just test-awscli` | permissive (sources `lib/ctf-env.sh` when configured) |
| [`sdk-test-java`](sdk-test-java/) | Java 17 / JUnit 5 | `just test-java` | permissive default |
| [`sdk-test-java`](sdk-test-java/) | Java 17 / JUnit 5 | `just test-ctf-java` | **required** (`IamEnforcementTest`, `CloudMapIamEnforcement`) |
| [`sdk-test-java`](sdk-test-java/) | Java 17 / JUnit 5 | `just test-cloudmap-java` | permissive (Cloud Map smoke) |
| [`sdk-test-go`](sdk-test-go/) | Go 1.24 / go test | `just test-go` | permissive |
| [`sdk-test-rust`](sdk-test-rust/) | Rust / cargo-nextest | `just test-rust` | permissive |
| [`compat-cdk`](compat-cdk/) | AWS CDK v2 | `just test-cdk` or `./run.sh` | use `FLOCI_CTF_PROFILE=ctf` + registered keys |
| [`compat-terraform`](compat-terraform/) | Terraform | `just test-terraform` or `./run.sh` | use `FLOCI_CTF_PROFILE=ctf` + registered keys |
| [`compat-opentofu`](compat-opentofu/) | OpenTofu | `just test-opentofu` or `./run.sh` | use `FLOCI_CTF_PROFILE=ctf` + registered keys |
| IaC (all) | CDK + TF + OpenTofu | `just test-compat` | same as individual IaC modules |

## Prerequisites

- **Floci running** on `http://localhost:4566` (or set `FLOCI_ENDPOINT`)
- **Docker** — required for Lambda invocation tests
- **just** — task runner for orchestration

Per-module requirements:

| Module | Requirements |
| --- | --- |
| `sdk-test-python` | Python 3.9+, pip |
| `sdk-test-node` | Node.js 20+, npm, vitest |
| `sdk-test-awscli` | AWS CLI v2, bash, jq |
| `sdk-test-java` | Java 17+, Maven |
| `sdk-test-go` | Go 1.24+ |
| `sdk-test-rust` | Rust (stable), Cargo, cargo-nextest |

## Setup

```bash
# Setup all SDKs
just setup

# Setup individual SDKs
just setup-python      # pip install -r requirements.txt
just setup-typescript  # npm install
just setup-awscli      # Clone bats-core, bats-support, bats-assert
```

## Running Tests

### All SDKs

```bash
just test-all
```

### Individual SDKs

```bash
# Python (pytest)
just test-python

# TypeScript (vitest)
just test-typescript

# AWS CLI (bats-core)
just test-awscli

# Java (full suite, permissive)
just test-java

# Java CTF enforcement probes
just test-ctf-java

# Cloud Map only
just test-cloudmap-java
```

### IaC compatibility

```bash
just test-compat          # CDK + Terraform + OpenTofu
just test-cdk
just test-terraform
just test-opentofu
```

Bats-based suites keep their normal console output and also write JUnit XML reports:

- `sdk-test-awscli/test-results/junit.xml`
- `compat-cdk/test-results/junit.xml`
- `compat-terraform/test-results/junit.xml`
- `compat-opentofu/test-results/junit.xml`

## Configuration

All modules read from environment variables. Copy `env.example` to `.env` (loaded by `just` via `dotenv-load`):

```bash
FLOCI_ENDPOINT=http://localhost:4566
AWS_DEFAULT_REGION=us-east-1

# Permissive profile (default for most suite tests):
FLOCI_CTF_PROFILE=permissive
FLOCI_IAM_ENFORCEMENT=false
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# CTF profile (floci-ctf Compose):
# FLOCI_CTF_PROFILE=ctf
# FLOCI_IAM_ENFORCEMENT=true
# AWS_ACCESS_KEY_ID=AKIA...
# AWS_SECRET_ACCESS_KEY=...
# FLOCI_AUTH_ROOT_ACCESS_KEY_ID=AKIA...
# FLOCI_AUTH_ROOT_SECRET_ACCESS_KEY=...
```

`FLOCI_IAM_ENFORCEMENT` is a documentation hint for CI and bash helpers; Java enforcement tests still detect enforcement at runtime via API behaviour.

## Running with Docker

Each module includes a `Dockerfile` for isolated execution:

```bash
# Python
docker build -t floci-sdk-python sdk-test-python/
docker run --rm --network host floci-sdk-python pytest

# TypeScript
docker build -t floci-sdk-node sdk-test-node/
docker run --rm --network host floci-sdk-node npm test
```

On macOS/Windows, use `host.docker.internal` instead of `localhost`:

```bash
docker run --rm -e FLOCI_ENDPOINT=http://host.docker.internal:4566 floci-sdk-python pytest
```

## Exit Codes

All test runners exit `0` on full pass and non-zero if any test fails — suitable for CI pipelines.
