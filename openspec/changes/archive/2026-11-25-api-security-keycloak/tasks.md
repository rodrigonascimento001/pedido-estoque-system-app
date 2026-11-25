## 1. Keycloak local infrastructure (repository: pedido-estoque-system-infra)

- [x] 1.1 Add a configurable Keycloak service to the infrastructure Compose stack on `pedido-estoque-network`, published on the documented local port, with a health check.
- [x] 1.2 Add realm bootstrap for `pedido-estoque`, the local public OIDC client using Authorization Code + PKCE S256, and the `pedido-estoque-api` audience mapper.
- [x] 1.3 Document local admin setup, development-user creation/login, issuer and internal JWKS URLs, environment configuration, startup order, and development-only credentials.

## 2. Gateway JWT validation (repository: pedido-estoque-system-app)

- [x] 2.1 Add Spring Security reactive OAuth2 resource-server support to `api-gateway` and configure issuer, JWK Set URI, and required audience through environment-backed properties.
- [x] 2.2 Replace the bearer-presence filter with JWT validation and ensure missing, malformed, invalid-signature, expired, not-yet-valid, wrong-issuer, and wrong-audience tokens receive HTTP 401 without forwarding.
- [x] 2.3 Preserve the intended public health behavior only on the isolated management listener; remove the `/actuator` exemption from the public API authentication path.
- [x] 2.4 Add focused gateway coverage for accepted and rejected tokens, including tokens signed by a test key and wrong issuer/audience cases.

## 3. Network and management isolation (repository: pedido-estoque-system-app)

- [x] 3.1 Remove host port mappings for `pedido-service` and `estoque-service` while preserving gateway-to-service connectivity over `pedido-estoque-network`.
- [x] 3.2 Configure distinct management ports for gateway and both services, expose only `health`, disable health details, and leave all management ports unpublished on the host.
- [x] 3.3 Update local API and Compose documentation to use the gateway and Keycloak login flow.
- [x] 3.4 Add configuration/Compose coverage for isolated management endpoints and the absence of host-published backend and management ports.

## 4. Integration and compatibility

- [x] 4.1 Verify the complete local flow: start infra, obtain a Keycloak user token, call APIs through port `8080`, and confirm direct backend host ports are unavailable.
- [x] 4.2 Verify health remains available only through the management listeners and unapproved Actuator endpoints are not exposed.
- [x] 4.3 Update the Insomnia collection or its usage instructions to obtain and attach the Keycloak access token.
