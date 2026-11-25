## ADDED Requirements

### Requirement: Validate Keycloak access tokens

The API gateway SHALL accept protected API requests only when the caller presents a Keycloak access token whose signature is valid against the configured Keycloak signing keys, whose time claims are valid, whose issuer matches the configured issuer, and whose audience includes the API audience. The initial authorization policy SHALL allow any otherwise valid token and SHALL NOT require roles or scopes.

#### Scenario: Request with a valid access token

- **WHEN** a caller sends a request to an application API with a valid Keycloak access token from the configured realm
- **THEN** the gateway forwards the request to the matching application service

#### Scenario: Request without a bearer token

- **WHEN** a caller sends a request to an application API without an Authorization bearer token
- **THEN** the gateway responds with HTTP 401 and does not forward the request

#### Scenario: Request with an invalid token

- **WHEN** a caller sends a request with a malformed, incorrectly signed, expired, not-yet-valid, wrong-issuer, or wrong-audience token
- **THEN** the gateway responds with HTTP 401 and does not forward the request

### Requirement: Support local user login through Keycloak

The local infrastructure SHALL provide a Keycloak instance with a bootstrapped `pedido-estoque` realm that allows a developer to authenticate as a user and obtain an access token for API calls. Local issuer and client settings SHALL be documented and configurable without changing application code.

#### Scenario: Developer obtains a local token

- **WHEN** a developer follows the documented local login flow
- **THEN** Keycloak issues an access token whose issuer matches the gateway configuration
- **AND** the developer can use that token to call the gateway APIs

### Requirement: Keep service APIs private to the Docker network

The local application Compose stack SHALL NOT publish the order or inventory service application ports on the host. The gateway SHALL continue to reach both services over the shared Docker network.

#### Scenario: Gateway forwards an authenticated request

- **WHEN** the gateway forwards a request to either backend service
- **THEN** it reaches that service through the shared Docker network

#### Scenario: Caller attempts direct host access

- **WHEN** a caller attempts to reach an order or inventory service through a host-published application port
- **THEN** no backend application port is available on the host
