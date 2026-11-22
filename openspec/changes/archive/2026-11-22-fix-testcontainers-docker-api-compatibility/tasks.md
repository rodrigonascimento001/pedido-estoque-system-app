## 1. Update Testcontainers compatibility

- [x] 1.1 Upgrade the centralized Testcontainers BOM to version 1.21.4.
- [x] 1.2 Run `mvn -B verify` and confirm all Docker-dependent integration tests start and pass.

## 2. Confirm CI behavior

- [x] 2.1 Run the existing GitHub Actions Maven verification workflow and confirm it completes without the Docker API version error.

## 3. Follow-ups found while verifying in CI

- [x] 3.1 Import `testcontainers-bom` before `spring-boot-dependencies` so the 1.21.4 version actually takes effect.
- [x] 3.2 Provide a default AWS region so the Spring Cloud AWS clients can be created outside the `aws` profile.
- [x] 3.3 Share a single Spring context between the `pedido-service` integration tests that consume Kafka.
- [x] 3.4 Make the `estoque-service` Kafka assertions filter records by `pedidoId`.
