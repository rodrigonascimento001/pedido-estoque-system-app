## Why

The gateway currently accepts any value with a `Bearer ` prefix, so callers can reach order and inventory APIs without a verifiable identity. The application services also publish their ports on the host, and management endpoints are exposed through application listeners; adding Keycloak-backed validation and narrowing these network surfaces establishes a real authentication boundary for the local system.

## What Changes

- Replace bearer-presence checking in the gateway with validation of Keycloak-issued JWT access tokens, including signature, expiration/not-before, and issuer checks.
- Configure a local Keycloak realm and user login flow, with documented development defaults and no role/scope requirement in this initial scope.
- Remove host-published ports for `pedido-service` and `estoque-service`; keep service-to-service traffic on the shared Docker network.
- Restrict Actuator exposure and move management endpoints for the gateway and both services to isolated management listeners that are not published on the host.
- Add the Keycloak container and local realm bootstrap to the separate `pedido-estoque-system-infra` repository, and document the combined local startup and token acquisition flow.
- **BREAKING** for local API consumers: calls through the gateway require a valid Keycloak access token, and direct host access to service ports is removed.

## Capabilities

### New Capabilities

- `keycloak-api-security`: Keycloak JWT validation at the gateway and authenticated access to application APIs.
- `isolated-management-endpoints`: restricted Actuator exposure on management listeners separated from application traffic.

### Modified Capabilities

- `separated-compose-stacks`: the local infrastructure stack provides Keycloak, and the application stack keeps backend services private to the shared Docker network.

## Impact

- `api-gateway`: authentication filter/security configuration, Keycloak issuer/JWKS settings, and management listener configuration.
- `docker-compose.yml`: remove host port mappings for backend services and configure application management listeners.
- `pedido-service` and `estoque-service`: Actuator exposure and management listener configuration.
- `pedido-estoque-system-infra` repository: Keycloak service, local realm/client/user bootstrap, network configuration, and developer documentation.
- Local API clients and instructions: obtain a user access token from Keycloak before calling `http://localhost:8080`.
