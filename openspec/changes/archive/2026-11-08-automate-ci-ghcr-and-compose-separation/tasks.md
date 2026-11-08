## 1. GitHub Actions validation and image publication

- [x] 1.1 Add a workflow that runs Java 21 `mvn -B verify` for pull requests targeting `develop` or `main` and pushes to those branches.
- [x] 1.2 Add main-only GHCR publication for the three Dockerfile module variants, gated on successful verification.
- [x] 1.3 Tag published images with the full commit SHA and moving `main` tag; use least-privilege workflow permissions and document the private-package prerequisite.

## 2. Separate the application Compose stack

- [x] 2.1 Adapt the application repository Compose file to contain only the three application services and retain local Dockerfile builds.
- [x] 2.2 Configure application containers to join the shared external network and use stable infrastructure service DNS names.
- [x] 2.3 Remove infrastructure resource and volume definitions from the application Compose file without changing application behavior.

## 3. Separate the infrastructure Compose stack

- [x] 3.1 In `C:\Users\rodrigo.nascimento\Desktop\development\repos\pedido-estoque-system-infra`, create or adapt the infrastructure Compose file for the databases, Kafka/Zookeeper, Redis, health checks, and persistent volumes currently needed by the application.
- [x] 3.2 Define the shared external Docker network and required DNS aliases in the infrastructure stack so the application stack can connect without duplicate infrastructure services.
- [x] 3.3 Document independent infrastructure startup and shutdown, preserving data volumes during normal stop operations.

## 4. Local workflow documentation and verification

- [x] 4.1 Document the two-repository local startup order, shared network contract, readiness checks, application startup, and independent shutdown commands.
- [x] 4.2 Run `mvn -B verify` and verify the documented local full-stack flow with both Compose projects.
- [x] 4.3 Confirm statically that a successful main workflow publishes all three private GHCR images with SHA and `main` tags, while develop does not publish images and no workflow deploys them.
