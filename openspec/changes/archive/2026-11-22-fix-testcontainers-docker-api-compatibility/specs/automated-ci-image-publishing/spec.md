## MODIFIED Requirements

### Requirement: Validate application changes with Maven
The application repository SHALL run `mvn -B verify` with Java 21 for changes targeting the `develop` and `main` branches. Verification SHALL complete successfully before images can be published. The Testcontainers client used by Docker-dependent integration tests SHALL be compatible with the Docker Engine API available on GitHub-hosted runners.

#### Scenario: Change is integrated into develop
- **WHEN** a change is pushed to or merged into `develop`
- **THEN** the workflow runs the complete Maven verify lifecycle, including the project's integration tests
- **AND** the workflow does not publish container images

#### Scenario: Change is integrated into main
- **WHEN** a change is pushed to or merged into `main`
- **THEN** the workflow runs the complete Maven verify lifecycle before starting image publication

#### Scenario: Docker-dependent tests run on a hosted runner
- **WHEN** the Maven verification job runs integration tests that start Testcontainers
- **THEN** the Testcontainers Docker client uses an API version accepted by the runner's Docker Engine
- **AND** the tests proceed to their assertions instead of failing during container startup due to an obsolete Docker API version
