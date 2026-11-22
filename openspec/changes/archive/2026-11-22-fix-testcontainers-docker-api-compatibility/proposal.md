## Why

The GitHub Actions `mvn -B verify` job fails when Testcontainers 1.19.7 sends Docker API 1.32 to a runner whose Docker daemon requires API 1.40 or newer. This prevents integration tests from starting even though the tests pass with the local Docker daemon.

## What Changes

- Upgrade Testcontainers to a version compatible with current Docker Engine API requirements.
- Keep the existing integration tests and `mvn -B verify` CI workflow intact, and verify the full Maven reactor after the upgrade.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `automated-ci-image-publishing`: require the CI integration-test environment to use a Testcontainers client compatible with the Docker Engine API available on GitHub-hosted runners.

## Impact

- Root `pom.xml` Testcontainers BOM version.
- CI verification through the existing Maven workflow.
- Existing Testcontainers integration tests in `pedido-service` and `estoque-service`.
