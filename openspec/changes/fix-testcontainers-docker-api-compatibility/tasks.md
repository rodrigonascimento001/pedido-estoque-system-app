## 1. Update Testcontainers compatibility

- [x] 1.1 Upgrade the centralized Testcontainers BOM to version 1.21.4.
- [x] 1.2 Run `mvn -B verify` and confirm all Docker-dependent integration tests start and pass.

## 2. Confirm CI behavior

- [ ] 2.1 Run the existing GitHub Actions Maven verification workflow and confirm it completes without the Docker API version error.
