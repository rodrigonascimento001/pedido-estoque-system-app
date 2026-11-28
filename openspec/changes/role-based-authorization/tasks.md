## 1. Realm roles (infrastructure repository)

- [ ] 1.1 Create realm roles `customer` and `operator` in the `pedido-estoque` realm bootstrap of `pedido-estoque-system-infra`.
- [ ] 1.2 Add `customer` to the realm default roles and assign it to existing accounts; keep `operator` out of the defaults.
- [ ] 1.3 Seed a local `operator` user for development with the password supplied through environment/secrets, nothing committed.
- [ ] 1.4 Confirm the roles appear in `realm_access.roles` of the access token for the API audience.

## 2. Gateway

- [ ] 2.1 Add a JWT authorities converter that reads the configured claim (`app.security.jwt.roles-claim`, default `realm_access.roles`) and maps roles to `ROLE_<nome>`.
- [ ] 2.2 Apply the route policy: `/api/estoque/**` requires `operator`; `/api/pedidos/**` requires `customer` or `operator`; keep registration and health rules unchanged.
- [ ] 2.3 Return `403` for a valid token without the required role (keep `401` for missing or invalid tokens) and log the route and missing role without token data.
- [ ] 2.4 Extend the internal-header filter to set `X-Authenticated-Roles` from the validated roles and to remove any client-sent value; add the constant to `common`.
- [ ] 2.5 Tests: policy matrix (customer, operator, no role, no token), forged roles header replaced or removed.

## 3. Order service

- [ ] 3.1 Add migration `V4__order_owner.sql` with nullable `owner_id` on `pedidos` and an index on it.
- [ ] 3.2 Store the owner on creation from `X-Authenticated-User`; return `400` when the header is missing.
- [ ] 3.3 Apply the read rule: owner or `operator` sees the order, everyone else gets `404` with the standard not-found body.
- [ ] 3.4 Add counter `order.access.denied` without user or id tags.
- [ ] 3.5 Update the Flyway baseline test to expect schema version 4.

## 4. Tests

- [ ] 4.1 Integration tests: owner reads, other customer gets `404`, operator reads any, legacy order visible only to operator, creation without user returns `400`.
- [ ] 4.2 Confirm idempotency tests still pass with the new required header.
- [ ] 4.3 Confirm the saga integration tests are unaffected.

## 5. Documentation and validation

- [ ] 5.1 Update README, `docs/EXECUCAO-LOCAL.md`, `docs/arquitetura.md` (role check and ownership in the creation and read sequences), `insomnia/README.md` and the collection (operator token, `403` case).
- [ ] 5.2 Update `AGENTS.md`: roles model, new header, backlog.
- [ ] 5.3 Run `mvn -B verify` and the local flow: customer creates and reads own order, another customer gets `404`, customer gets `403` on stock, operator manages stock.
