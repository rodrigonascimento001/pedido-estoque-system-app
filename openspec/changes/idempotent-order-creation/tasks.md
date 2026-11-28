## 1. Gateway: authenticated subject for downstream services

- [ ] 1.1 Add a gateway filter that removes any client-sent `X-Authenticated-User` header and sets it from the validated JWT `sub` on routes to `pedido-service`.
- [ ] 1.2 Test that a forged header is replaced and that unauthenticated requests are still rejected before reaching the service.

## 2. Persistence

- [ ] 2.1 Add migration `V3__idempotency_key.sql` with table `idempotency_key` (`scope`, `idem_key`, `request_hash`, `pedido_id`, `created_at`, `expires_at`), primary key `(scope, idem_key)` and an index on `expires_at`.
- [ ] 2.2 Add the repository with an atomic `INSERT ... ON CONFLICT DO NOTHING` that also takes over a row whose `expires_at` has passed.

## 3. Order service

- [ ] 3.1 Read and validate `Idempotency-Key` (format rules) and `X-Authenticated-User`; return `400` for a bad key or a key without subject.
- [ ] 3.2 Compute the SHA-256 request hash from the canonical `PedidoRequest`.
- [ ] 3.3 In the same transaction as order creation: claim the key, create the order and outbox event, link `pedido_id`; on conflict return the stored order, or `422` when the hash differs.
- [ ] 3.4 Return `201` with `Idempotent-Replayed: true` on replay and keep the no-key path unchanged.
- [ ] 3.5 Add the retention property `app.idempotency.ttl` (24h) and a scheduled cleanup job that deletes expired rows in batches.
- [ ] 3.6 Add counters `pedido.idempotency.replay` and `pedido.idempotency.conflict`; keep the key out of tags and logs.

## 4. Tests

- [ ] 4.1 Integration tests (Testcontainers): first request, replay, different body (`422`), no key, invalid key, failed creation does not consume the key.
- [ ] 4.2 Concurrency test: parallel requests with the same key create exactly one order and one outbox event.
- [ ] 4.3 Scope test: two users with the same key value get distinct orders.
- [ ] 4.4 Expiry and cleanup tests.

## 5. Documentation and validation

- [ ] 5.1 Update `docs/arquitetura.md` (creation sequence with the key check), the README, `docs/EXECUCAO-LOCAL.md`, `insomnia/README.md` and the Insomnia collection with the header and the replay example.
- [ ] 5.2 Note in `AGENTS.md` that the internal header depends on services staying private, and mark backlog item A3 as in progress.
- [ ] 5.3 Run `mvn -B verify` and the local flow: create an order twice with the same key and confirm a single order.
