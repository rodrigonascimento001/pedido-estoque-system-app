## Context

The Maven reactor runs integration tests with Testcontainers 1.19.7. In GitHub Actions, that version's Docker client sends API 1.32, while the hosted Docker daemon in the failed run requires at least API 1.40. The failure occurs before test logic: Postgres, Kafka, and LocalStack containers cannot start. The same suite passes locally because the local Docker daemon still accepts API 1.32.

## Goals / Non-Goals

**Goals:**
- Make Testcontainers integration tests compatible with the Docker API available on the GitHub-hosted runner.
- Preserve the existing Testcontainers-based integration coverage and CI workflow.

**Non-Goals:**
- Change application Kafka behavior or integration test scenarios.
- Skip Docker-dependent tests in CI or move them to another service.
- Upgrade Spring Boot or migrate to Testcontainers 2.x.

## Decisions

- Upgrade the Testcontainers BOM from 1.19.7 to 1.21.4. This stays on the 1.x API line and its release notes explicitly address compatibility with recent Docker Engine changes.
- Keep Docker API negotiation owned by the Testcontainers client. A project-level `docker-java.properties` API pin is an alternative workaround, but it can become stale as runner Docker versions change.
- Run the full `mvn -B verify` reactor after the upgrade, since both service modules use Testcontainers and CI already gates image publication on this command.

## Risks / Trade-offs

- [A Testcontainers patch-level upgrade may alter transitive test dependencies] → Run the full Maven verification lifecycle and review dependency resolution before considering the change ready.
- [The hosted runner's Docker API minimum may advance again] → Use a maintained Testcontainers 1.x release rather than pinning a fixed Docker API version in project resources.

## Migration Plan

No application data or runtime migration is required. Update the centralized Testcontainers version, run `mvn -B verify`, then let the existing GitHub Actions workflow repeat the same verification on the branch.

## Open Questions

None.
