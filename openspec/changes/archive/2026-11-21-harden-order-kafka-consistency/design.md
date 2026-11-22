## Context

`pedido-service` currently sends `PedidoCriadoEvent` before its database transaction commits. `estoque-service` then reserves products in independent transactions and compensates business failures item by item. A transient failure after one successful item can leave partial stock changes. Both producers currently log before broker acknowledgement, and the order consumer discards inventory outcomes for orders it cannot find. Kafka redelivery can repeat effects because reservation and order state changes are not idempotent.

Both services have their own PostgreSQL database. Kafka delivery is at least once. The local environment uses Docker Compose, Testcontainers integration tests already exist, and Datadog monitor definitions are maintained as JSON and imported manually.

## Goals / Non-Goals

**Goals:**

- Make an inventory decision for all normalized items of an order atomic and uniquely recorded by `pedidoId`.
- Ensure stock never falls below zero, including concurrent reservations.
- Store accepted or rejected outcomes idempotently and publish them reliably.
- Ensure order events are emitted only for committed orders and survive Kafka outages.
- Prevent duplicate or contradictory outcome events from changing a terminal order or inflating metrics.
- Keep exhausted retry records visible in DLT and alert through the existing Datadog monitor workflow.
- Introduce Flyway while preserving existing local databases and validating schemas.

**Non-Goals:**

- HTTP `Idempotency-Key` for `POST /api/pedidos`.
- JWT validation, direct service network exposure, actuator security, or server-side product pricing.
- A DLT replay UI/command-line tool, automatic DLT replay, malformed-message deserialization, or explicit topic partition/scaling policy.
- Exactly-once delivery across PostgreSQL and Kafka. The design provides durable at-least-once publication with idempotent consumers.

## Decisions

### Reservation identity and item normalization

Use a reservation header with a unique `pedido_id` and reservation details unique on `(pedido_id, produto_id)`. Before checking stock, aggregate repeated product lines by summing their positive quantities and sort product IDs for stable lock ordering. Store a canonical fingerprint of the normalized product/quantity set with the header. Price is not part of the inventory fingerprint because inventory availability depends only on product and quantity.

If the same order ID and normalized item set arrives again, return the stored result without touching stock or emitting another logical outcome. If the same order ID arrives with a different normalized set, reject it as an idempotency conflict and leave the existing reservation unchanged.

### All-or-nothing stock transaction

Process reservation inside one PostgreSQL transaction. Lock existing stock rows in stable product ID order, determine whether any product is missing or has insufficient quantity, and perform no decrement if the complete order cannot be fulfilled. Persist the rejected header, requested item details, and `EstoqueIndisponivelEvent` outbox row in that transaction. For a fulfillable order, use a conditional update equivalent to `UPDATE estoque SET quantidade_disponivel = quantidade_disponivel - :q, versao = versao + 1 WHERE produto_id = :id AND quantidade_disponivel >= :q`; require exactly one updated row per product, then persist reservation details and the success outbox row before commit.

The conditional update remains the final protection against overselling. Row locks serialize competing decisions and allow a business rejection to be recorded without first applying partial decrements and then compensating. A conditional update that unexpectedly affects zero rows after the locked check is treated as a transient consistency failure, rolling back the full transaction for retry. Increment the existing `@Version` column on successful SQL updates so entity-based stock adjustments remain version-aware.

### Transactional outbox and relay

Each service owns an outbox table in its own database. The order transaction writes the order and `PedidoCriadoEvent` outbox row together. The inventory transaction writes the reservation result and corresponding outcome event together. Do not publish directly from those business transactions or rely on `@TransactionalEventListener(AFTER_COMMIT)`, because a process failure after commit can still lose an event.

Use a small polling relay in each service rather than CDC/Debezium, avoiding new infrastructure services. The relay claims a bounded batch with `FOR UPDATE SKIP LOCKED`, a claim identifier, and a lease expiry; it commits the claim, sends to Kafka, waits for broker acknowledgement, then marks the row published. A failed send or expired claim remains eligible for retry. A crash after broker acknowledgement but before marking the row can publish a duplicate; stable event IDs, reservation uniqueness, and guarded order transitions make that safe. Store attempt count and last failure for diagnosis. Relay batch size and polling/lease durations are configuration properties with conservative defaults.

### Consumer retry and dead-letter visibility

Keep business rejections as successful processing outcomes; do not retry insufficient stock or missing products. Retry technical failures with configurable retry settings, preserving the current three-attempt/two-second baseline unless configuration overrides it. Listener failures must escape the listener so the offset is not acknowledged before the database transaction commits.

An outcome event for a temporarily missing order is retryable. After exhaustion, leave the order unchanged and retain the event in DLT. Add a DLT handler that logs source/DLT topic, key, partition, offset, and cause without logging the full payload, and increments a low-cardinality Micrometer counter tagged by service and source topic. Add a Datadog query monitor for any DLT record in a rolling five-minute window, following the existing manual JSON import process. There is no automatic state transition or DLT replay UI in this change; operations can inspect and manually reprocess the retained record after resolving the cause.

### Guarded order transitions

Change `Pedido.confirmar()` and `Pedido.cancelar()` to report whether they transitioned from `PENDENTE`. Repeated matching outcomes are no-ops. A conflicting event after a terminal outcome leaves the state unchanged and emits a conflict log/metric, but does not retry indefinitely. Increment outcome counters and record saga duration only when a transition was applied.

### Flyway and existing databases

Add Flyway to both services, capture the existing schema in version 1 migrations, and add reservation/outbox structures in a later migration. Configure baseline-on-migrate at version 1 so a local database already created by Hibernate keeps its rows and proceeds to the new migration; an empty database applies version 1 and subsequent migrations normally. Set Hibernate `ddl-auto: validate` after the migration path is in place. Validate local database schema/data preservation as part of migration tests.

Do not automatically drop migration-created structures during rollback. Reverting application code leaves the additive tables and outbox rows intact; operators must not remove unprocessed outbox data during rollback.

## Risks / Trade-offs

- **[The relay can publish a duplicate after broker acknowledgement and before the outbox update commits]** → Keep stable event IDs and enforce idempotent reservation and state-transition handling.
- **[Holding row locks while checking and updating products can reduce throughput for hot products]** → Aggregate duplicate lines, lock rows in deterministic order, and keep the transaction limited to stock checks, updates, reservation records, and outbox writes.
- **[A DLT record can leave an order pending; a failed inventory response can also leave stock reserved while the order is pending]** → Emit DLT telemetry and alert, retain the event for manual reprocessing, and track a replay tool in `AGENTS.md`.
- **[An existing local database may have drifted from the entity-derived schema]** → Baseline it at version 1 without dropping data, then let Hibernate validation fail with a clear mismatch before application traffic is accepted.
- **[Datadog monitor notifications depend on the organization's notification routing]** → Provide the monitor JSON and document manual import; configure recipients in Datadog outside the repository.
- **[Polling adds database reads and retains outbox rows]** → Use bounded polling/claim batches and expose attempts/failures; cleanup/retention can be tuned after observing volume.

## Migration Plan

1. Add version 1 Flyway migrations describing current tables and configure baseline version 1. Existing local schemas are baselined without dropping data; fresh databases create the same schema from migration files.
2. Add additive reservation/outbox migrations and confirm Hibernate `validate` succeeds against both upgraded and fresh databases.
3. Deploy the order and inventory services with outbox relays and idempotent consumers. Unpublished outbox rows remain retryable across restarts.
4. Import the DLT monitor JSON in Datadog and verify the counter is visible before relying on alert notifications.
5. Roll back application binaries only if necessary. Keep migration-created tables and unpublished outbox rows; do not run destructive down migrations.

## Open Questions

No product decisions remain open. The exact polling/lease defaults and retry property names are implementation values and should be conservative, configurable, and covered by integration tests.
