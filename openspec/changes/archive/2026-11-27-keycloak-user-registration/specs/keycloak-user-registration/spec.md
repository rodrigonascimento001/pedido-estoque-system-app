## ADDED Requirements

### Requirement: Public user registration creates an account in the application realm
The system SHALL expose a user registration operation that accepts a username, email address, and password without requiring an access token. The operation SHALL create an enabled user in the configured `pedido-estoque` realm so the user can authenticate through the existing Authorization Code + PKCE flow. The password SHALL be write-only and SHALL NOT be returned or persisted by the application.

#### Scenario: User registers successfully
- **WHEN** a caller submits valid, non-duplicate username, email, and password to `POST /api/usuarios`
- **THEN** the service creates an enabled user in the configured Keycloak realm and returns `201 Created` with a non-secret user identifier and username
- **AND** the created user can authenticate using the existing Keycloak login flow

#### Scenario: Registration does not require a token
- **WHEN** a caller submits a valid registration request without an Authorization header
- **THEN** the gateway routes the request to the registration service
- **AND** requests to other protected API routes continue to require a valid JWT

#### Scenario: Duplicate identity is rejected
- **WHEN** a registration request uses a username or email already registered in the realm
- **THEN** the service returns `409 Conflict` without creating another account
- **AND** the response does not expose Keycloak internals or credentials

#### Scenario: Invalid registration data is rejected
- **WHEN** the request omits a required field or violates input/password policy
- **THEN** the service returns `400 Bad Request` with safe field-level guidance
- **AND** no user is created

#### Scenario: Keycloak is unavailable
- **WHEN** the service cannot reach the configured Keycloak Admin API
- **THEN** it returns a controlled server error and does not report that registration succeeded

### Requirement: Administrative access is isolated and least-privileged
The registration service SHALL use a dedicated confidential service client and client-credentials flow to call the Keycloak Admin API in the application realm. It SHALL NOT use the Keycloak administrator's username/password, expose its client secret to callers, or grant broader realm administration than needed to create and inspect users.

#### Scenario: Service authenticates to the Admin API
- **WHEN** the registration service creates a user
- **THEN** it obtains/uses an access token for its dedicated service client and calls the Admin API for the configured application realm
- **AND** the client secret is supplied by external configuration and is not committed to the repository

#### Scenario: Admin API audience and permissions are provisioned
- **WHEN** the dedicated service client requests a token with client credentials
- **THEN** the token includes the `kc-admin-api` scope/audience required by the Keycloak Admin API
- **AND** the service account has only the `realm-management.manage-users` role

#### Scenario: Administrative credentials are protected
- **WHEN** registration succeeds or fails
- **THEN** neither the user's password nor the service client's secret/token is included in responses, application logs, metrics, or traces

### Requirement: Public registration is rate-limited and network-isolated
The system SHALL apply the gateway's IP-based rate limit to the public registration operation. The registration service SHALL be reachable by the gateway over the Docker network and SHALL NOT publish its application port directly to the host.

#### Scenario: Registration traffic exceeds the limit
- **WHEN** a caller exceeds the configured per-IP registration rate limit
- **THEN** the gateway rejects the request using the rate-limit response
- **AND** it does not forward the excess request to Keycloak

#### Scenario: Registration service is accessed through the gateway
- **WHEN** a host client attempts to reach the registration service directly
- **THEN** no host-published service port is available
- **AND** registration remains available through the configured gateway route
