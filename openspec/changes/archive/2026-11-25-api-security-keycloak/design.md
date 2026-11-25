## Context

- `api-gateway` currently uses a custom `GlobalFilter` that permits any `Authorization` value beginning with `Bearer `; it does not verify JWT structure, signature, expiration, or issuer.
- The application Compose file publishes `pedido-service` on host port `8081` and `estoque-service` on `8082`, allowing callers to bypass the gateway.
- The gateway exposes `health`, `gateway`, and `metrics` through its application listener. Each service exposes `health`, `info`, and `metrics` through its application listener. The gateway filter currently exempts every path beginning with `/actuator`.
- The separate `pedido-estoque-system-infra` repository owns the shared `pedido-estoque-network` and already documents how the app stack connects to infrastructure.
- The stack uses Java 21, Spring Boot 3.2.5, Spring Cloud Gateway/WebFlux, Maven, and Compose.

## Goals / Non-Goals

**Goals:**

- Validate Keycloak access tokens at the gateway using the realm's signing keys and checks for issuer, time claims, and API audience.
- Allow authenticated users from the configured realm without role- or scope-based authorization in this initial change.
- Make backend application ports reachable only from the shared container network.
- Limit Actuator to health checks on separate, unpublished management listeners.
- Provide a reproducible local Keycloak realm and user-login setup in the infrastructure repository.

**Non-Goals:**

- Role/permission authorization, per-endpoint policies, service-to-service authentication, refresh-token policy, or production Keycloak deployment.
- Requiring each backend service to validate JWTs independently; the gateway is the HTTP trust boundary for this scope.
- Publishing a management interface or adding a separate metrics scraping path. Existing StatsD/Datadog metric export remains independent of the Actuator HTTP endpoint.
- Editing the infrastructure repository from this application-repository change branch. The corresponding infra tasks are specified here and must be implemented on a branch in that repository.

## Decisions

### D1: Use Spring Security's reactive OAuth2 resource server support

Add the Spring Boot OAuth2 resource-server support to `api-gateway` and configure JWT decoding with an explicit issuer, JWK Set URI, and required audience. Replace the bearer-presence `GlobalFilter` with the standard reactive resource-server authentication path. This delegates signature, `exp`, and `nbf` verification to maintained Spring Security components and keeps gateway request handling non-blocking.

The decoder SHALL validate `iss` against `http://localhost:8180/realms/pedido-estoque` by default and retrieve keys from `http://keycloak:8080/realms/pedido-estoque/protocol/openid-connect/certs` by default. Both values are environment-configurable. Supplying the internal JWK URI separately avoids requiring Docker containers to resolve the host's `localhost` while the token's issuer remains the browser-accessible local URL.

Alternatives considered:

- Hand-written JWT parsing or signature checks: rejected because it duplicates security-sensitive protocol behavior.
- Introspection on every API request: rejected for the local signed-token flow because it adds a synchronous Keycloak dependency to request availability; local signing keys are obtained through JWKS and can be cached by the decoder.

### D2: Require a dedicated API audience, but no roles/scopes

Configure the realm to add `pedido-estoque-api` to access-token audiences and require that audience at the gateway. Any token issued by the realm that is valid for this API is accepted; roles and scopes do not gate requests. Audience validation prevents a valid token issued for an unrelated client from being treated as an API token.

Alternatives considered:

- Accept every token with the correct issuer: rejected because realm tokens can be intended for other clients.
- Add role/scope authorization now: deferred per the requested initial policy.

### D3: Use a local public OIDC client with Authorization Code and PKCE

The infra realm bootstrap SHALL define a public local API client for user login with Authorization Code and PKCE (S256), plus an audience mapper for `pedido-estoque-api`. Do not use a client secret in a developer machine or resource-owner-password grant. For the current Insomnia collection, allow only its documented redirect URI `https://app.insomnia.rest/oauth/redirect`. The infra documentation will describe creating a development user in the local realm and obtaining a token through the API client workflow.

### D4: Make the local issuer browser-reachable and JWK retrieval container-reachable

Use local Keycloak host port `8180` and issuer `http://localhost:8180/realms/pedido-estoque`. The Keycloak service joins `pedido-estoque-network` under the DNS name `keycloak`; the gateway uses the internal JWKS URL above. Realm import/bootstrap and local admin configuration belong to the infrastructure repository. Host bootstrap credentials are development-only and configurable through an ignored `.env` file or documented environment variables.

Alternatives considered:

- Use `http://keycloak:8080` as issuer: rejected because browsers and host-based clients cannot resolve the Compose service name, and the JWT issuer must match the externally used realm issuer.
- Use host networking from the gateway to fetch JWKS: rejected because Docker service DNS already provides the internal path.

### D5: Remove backend host port publication

Delete the `ports` mappings for `pedido-service` and `estoque-service`; retain their application listeners and service DNS names so only containers on the shared network (including the gateway) can reach them. The gateway remains published on host port `8080`.

### D6: Isolate and narrow management endpoints

Set a distinct management port for each Spring application, expose only `health`, disable detailed health data, and do not publish management ports in the app Compose file. Keep management ports on the container network for future internal operations. Remove the gateway's public `/actuator` bypass because actuator routes are no longer served on the application listener. StatsD export remains the metrics path for Datadog.

Alternatives considered:

- Keep Actuator on the application listener and rely only on the authentication filter: rejected because operational endpoints should not share the public API listener and the path exemption currently bypasses authentication.
- Expose `gateway` or raw `metrics`: rejected because they disclose routes and operational detail without being needed for the local health path.

## Risks / Trade-offs

- [Keycloak is unavailable during initial login or key rotation] → The gateway SHALL use a configured JWK Set URI with normal key caching; unauthenticated or unverifiable requests fail closed. App startup should not require a live Keycloak call.
- [Issuer, internal JWKS URL, or audience differs across deployments] → Externalize all three values and document matching requirements; keep local defaults in Compose.
- [Removing host ports breaks clients that called backend services directly] → Update local API instructions to use the gateway on `8080`; direct access is intentionally removed.
- [Separate management listeners are reachable by other containers on the shared network] → Expose only health, publish no ports, and disable health details. A narrower network or authenticated operations plane can be introduced if the deployment model later requires it.
- [Infra and app repositories can be updated at different times] → Bring up Keycloak before relying on authenticated local API calls; coordinate the two repository branches and document startup order.

## Migration Plan

1. Add the Keycloak service, realm/client bootstrap, development-user instructions, and health check in the infrastructure repository.
2. Configure the gateway resource server and management listeners, update service management settings, and remove backend host port mappings in the application repository.
3. Update local API instructions and client examples to obtain a user token and call the gateway.
4. Start infrastructure first, then the application stack. Rollback consists of reverting both repository changes together; do not restore backend host exposure as a permanent workaround.

## Open Questions

- A deployed environment must provide its stable external Keycloak issuer, internal JWKS address, and API audience through configuration; the local defaults do not dictate production URLs.
