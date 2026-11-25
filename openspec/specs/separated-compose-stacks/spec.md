# separated-compose-stacks Specification

## Purpose
Keep application and infrastructure Compose projects separate while documenting their shared local development flow.
## Requirements
### Requirement: Keep application and infrastructure Compose definitions separate
The application repository SHALL define only application services in its Compose stack. The infrastructure repository SHALL define infrastructure resources, including the databases, Kafka dependencies, and Redis used by the local application flow. Infrastructure service definitions and persistent volumes SHALL NOT be duplicated in the application Compose stack.

#### Scenario: Application stack is started against running infrastructure
- **WHEN** the infrastructure Compose project is running and healthy
- **THEN** the application Compose project can start the gateway and both application services
- **AND** the application services connect to infrastructure through documented Docker DNS names

### Requirement: Support the complete principal local flow
The repositories SHALL document how to start the infrastructure and application Compose projects together for the principal local flow. The flow SHALL use an explicitly named shared external Docker network and start infrastructure before dependent application services.

#### Scenario: Developer starts the complete local flow
- **WHEN** a developer follows the documented local startup procedure
- **THEN** the infrastructure services start and become healthy before application startup
- **AND** the API gateway, order service, and inventory service can communicate with the required infrastructure and with each other

#### Scenario: Developer stops the local flow
- **WHEN** a developer follows the documented shutdown procedure
- **THEN** both Compose projects can be stopped independently
- **AND** stopping the projects does not remove persistent infrastructure data volumes

### Requirement: Keep local application builds available
The application Compose project SHALL retain a way to build the application services from the local source tree for development. This local build path SHALL remain separate from the GHCR publication workflow and SHALL NOT imply production readiness.

#### Scenario: Build and run application services locally
- **WHEN** a developer starts the application Compose project from the application repository
- **THEN** the three services can be built from the repository Dockerfile with the appropriate module selection
- **AND** infrastructure is provided by the separate infrastructure Compose project

### Requirement: Provide local Keycloak in the infrastructure stack

The infrastructure Compose project SHALL provide a local Keycloak instance on the shared Docker network, with a bootstrapped `pedido-estoque` realm and documented local user authentication settings. The Keycloak image, exposed host port, realm import, and local credentials SHALL be configurable or explicitly documented as development-only defaults.

#### Scenario: Application gateway validates a local token

- **WHEN** the infrastructure stack is running and a developer obtains a token from its Keycloak realm
- **THEN** the gateway can retrieve the realm signing keys over the shared Docker network and validate that token

#### Scenario: Infrastructure and application stacks run independently

- **WHEN** the infrastructure stack is started before the application stack
- **THEN** Keycloak and the application containers join or resolve through the documented shared network
- **AND** the application stack can start without publishing backend application or management ports on the host

