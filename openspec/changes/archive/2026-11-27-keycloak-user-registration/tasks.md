## 1. Keycloak service identity and infrastructure

- [x] 1.1 Add a dedicated confidential service client for user registration to the `pedido-estoque` realm configuration in `pedido-estoque-system-infra`.
- [x] 1.2 Grant only the realm-management permissions required to create and inspect users; document local secret setup without committing credentials.
- [x] 1.3 Configure the registration service's internal Keycloak Admin API URL, realm, client ID, and client secret through environment/secrets configuration.

## 2. Registration service

- [x] 2.1 Add a Spring Boot `user-registration-service` module to the Maven build and application Compose, attached to the shared private network with no host-published application port.
- [x] 2.2 Implement `POST /api/usuarios` with request validation for username, email, and password; return a minimal `201 Created` representation without the password.
- [x] 2.3 Integrate with Keycloak Admin API using the dedicated client-credentials identity; create enabled users with a non-temporary password and no additional roles.
- [x] 2.4 Map duplicate username/email to `409`, invalid input/password policy to `400`, and Keycloak/network failures to controlled errors without leaking secrets or server details.
- [x] 2.5 Ensure request bodies, passwords, service tokens, and client secrets are excluded from logs, metrics, and traces.

## 3. Gateway routing and protection

- [x] 3.1 Add a route from `POST /api/usuarios` to the registration service and permit anonymous access only for that operation; preserve JWT validation for all other API routes.
- [x] 3.2 Apply the existing IP-based rate limiter to public registration and verify excess requests are rejected before reaching the service.
- [x] 3.3 Keep the registration service inaccessible directly from the host and reachable only over the application Docker network.

## 4. Validation and documentation

- [x] 4.1 Add service tests for successful creation, duplicate account, invalid input, Admin API failure, and sensitive-data redaction.
- [x] 4.2 Validate the integrated flow: register a new user, obtain a token with Authorization Code + PKCE, then call a protected API route successfully.
- [x] 4.3 Update local setup and Insomnia instructions/collection with the registration request and explain the service-client configuration and local development setup.
- [x] 4.4 Run `mvn -B verify` and validate both application and infrastructure Compose configurations.
