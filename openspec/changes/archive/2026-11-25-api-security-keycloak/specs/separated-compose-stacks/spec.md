## ADDED Requirements

### Requirement: Provide local Keycloak in the infrastructure stack

The infrastructure Compose project SHALL provide a local Keycloak instance on the shared Docker network, with a bootstrapped `pedido-estoque` realm and documented local user authentication settings. The Keycloak image, exposed host port, realm import, and local credentials SHALL be configurable or explicitly documented as development-only defaults.

#### Scenario: Application gateway validates a local token

- **WHEN** the infrastructure stack is running and a developer obtains a token from its Keycloak realm
- **THEN** the gateway can retrieve the realm signing keys over the shared Docker network and validate that token

#### Scenario: Infrastructure and application stacks run independently

- **WHEN** the infrastructure stack is started before the application stack
- **THEN** Keycloak and the application containers join or resolve through the documented shared network
- **AND** the application stack can start without publishing backend application or management ports on the host
