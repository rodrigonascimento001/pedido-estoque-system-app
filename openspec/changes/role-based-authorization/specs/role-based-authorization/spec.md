## ADDED Requirements

### Requirement: Business roles are defined in the realm
The `pedido-estoque` realm SHALL define the realm roles `customer` and `operator`. Every newly registered account SHALL receive `customer` and SHALL NOT receive `operator`. The `operator` role SHALL be assignable only through Keycloak administration.

#### Scenario: New account gets the customer role
- **WHEN** a user registers through `POST /api/usuarios` and logs in
- **THEN** the access token lists `customer` in the configured roles claim
- **AND** it does not list `operator`

### Requirement: The gateway enforces a role policy per route
The gateway SHALL read the caller's roles from the configured claim (`realm_access.roles` by default) and SHALL apply this policy to otherwise valid tokens: `/api/estoque/**` requires `operator`; `/api/pedidos/**` requires `customer` or `operator`. A valid token that lacks the required role SHALL receive `403 Forbidden` and the request SHALL NOT be forwarded.

#### Scenario: Customer cannot manage stock
- **WHEN** a caller with only `customer` sends `POST /api/estoque`
- **THEN** the gateway responds `403` and does not forward the request

#### Scenario: Operator manages stock
- **WHEN** a caller with `operator` sends `POST /api/estoque`
- **THEN** the gateway forwards the request to the inventory service

#### Scenario: Customer creates an order
- **WHEN** a caller with `customer` sends `POST /api/pedidos`
- **THEN** the gateway forwards the request to the order service

#### Scenario: Valid token without business roles
- **WHEN** a caller presents a valid token with neither `customer` nor `operator` and calls `/api/pedidos`
- **THEN** the gateway responds `403`

#### Scenario: Missing token is still unauthorized
- **WHEN** a caller sends no token
- **THEN** the gateway responds `401`, not `403`

### Requirement: Roles reach the services through a trusted internal header
The gateway SHALL pass the caller's roles to downstream services in the `X-Authenticated-Roles` header as a comma-separated list, and SHALL discard any value of that header sent by the client, including when no user is authenticated.

#### Scenario: Client forges the roles header
- **WHEN** a client sends `X-Authenticated-Roles: operator` with a token that has only `customer`
- **THEN** the service receives `X-Authenticated-Roles: customer`

#### Scenario: Forged header without authentication
- **WHEN** a request without an authenticated user carries `X-Authenticated-Roles`
- **THEN** the header is removed before forwarding

### Requirement: Denied access is observable without leaking identities
The gateway SHALL log each `403` with the route and the missing role and SHALL NOT log the token. The order service SHALL count denied order reads in `order.access.denied` without using the user or order id as a tag.

#### Scenario: Denial is logged without the token
- **WHEN** the gateway returns `403` for a missing role
- **THEN** the log line names the route and the required role and contains no token or claim values
