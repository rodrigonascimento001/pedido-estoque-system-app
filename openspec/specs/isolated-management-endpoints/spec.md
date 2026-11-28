# isolated-management-endpoints Specification

## Purpose
Define which management endpoints are exposed and keep management traffic isolated from application API listeners.

## Requirements
### Requirement: Restrict Actuator endpoints

The gateway, order service, and inventory service SHALL expose only the explicitly approved Actuator endpoints. Operational endpoints such as gateway route details and raw metrics SHALL NOT be exposed through the public application listener.

#### Scenario: Public caller requests an operational endpoint

- **WHEN** a caller requests an unapproved Actuator endpoint through an application listener
- **THEN** the endpoint is unavailable

#### Scenario: Health endpoint remains available to local operations

- **WHEN** a local operational check accesses the configured management listener
- **THEN** it can read the approved health endpoint

### Requirement: Isolate management listeners

Each Spring application SHALL serve management endpoints on a listener separate from its application API listener. The local application Compose stack SHALL NOT publish management ports on the host, and management traffic SHALL remain on the container or internal operational path.

#### Scenario: Application API listener does not serve management traffic

- **WHEN** a caller requests an Actuator endpoint from a gateway or backend application port
- **THEN** the management endpoint is not served by that listener

#### Scenario: Management ports are not host-published

- **WHEN** the local application Compose stack is started
- **THEN** gateway and backend management ports are not published on the host
