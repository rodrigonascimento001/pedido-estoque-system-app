## MODIFIED Requirements

### Requirement: Validate Keycloak access tokens

The API gateway SHALL accept protected API requests only when the caller presents a Keycloak access token whose signature is valid against the configured Keycloak signing keys, whose time claims are valid, whose issuer matches the configured issuer, and whose audience includes the API audience. A token that passes these checks SHALL then be subject to the role policy of the `role-based-authorization` capability; a valid token without the role required by the route SHALL receive `403`.

#### Scenario: Request with a valid access token

- **WHEN** a caller sends a request to an application API with a valid Keycloak access token from the configured realm and the role required by the route
- **THEN** the gateway forwards the request to the matching application service

#### Scenario: Request without a bearer token

- **WHEN** a caller sends a request to an application API without an Authorization bearer token
- **THEN** the gateway responds with HTTP 401 and does not forward the request

#### Scenario: Request with an invalid token

- **WHEN** a caller sends a request with a malformed, incorrectly signed, expired, not-yet-valid, wrong-issuer, or wrong-audience token
- **THEN** the gateway responds with HTTP 401 and does not forward the request

#### Scenario: Valid token without the required role

- **WHEN** a caller sends a valid token that lacks the role required by the route
- **THEN** the gateway responds with HTTP 403 and does not forward the request
