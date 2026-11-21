## Why

Kafka delivers messages at least once, while inventory is currently reserved item by item in separate transactions. A retry or duplicate can leave partial or duplicate reservations, and event loss can leave an order pending indefinitely. This change makes reservation and event processing atomic, idempotent, observable, and safe to retry.

## What Changes

- Reserve all normalized items for an order in one inventory database transaction, keyed by the order ID, with a persisted result for both accepted and rejected reservations.
- Decrement stock with conditional atomic updates. A missing product or insufficient balance rejects the complete reservation without leaving any stock decremented.
- Add transactional outboxes to `pedido-service` and `estoque-service`; relays publish only committed events and retry broker failures.
- Make event consumers idempotent. Retry transient processing failures; after exhaustion, retain the record in the DLT, emit telemetry and an alert, and leave the order without an automatic state transition.
- Guard order transitions so only `PENDENTE` can become `CONFIRMADO` or `CANCELADO`; count outcome metrics only for an effective transition.
- Use Flyway migrations for existing and new schemas, preserving initialized local databases and validating the resulting schema.
- Add integration coverage for duplicate delivery, mid-order business rejection, duplicate product lines, concurrent reservations, outbox retry, DLT visibility, and repeated/conflicting outcome events.

Authentication/security, server-side price lookup, HTTP `Idempotency-Key`, a DLT replay UI/tool, and poison-message deserialization/topic topology are out of scope. They are recorded in the repository backlog in `AGENTS.md`.

## Capabilities

### New Capabilities
- `idempotent-inventory-reservation`: Atomic, order-level stock reservation and persisted outcomes that tolerate duplicate events.
- `reliable-order-events`: Durable event publication, retry handling, and DLT visibility for the order/inventory flow.
- `guarded-order-transitions`: Valid order state transitions and metrics that reflect only effective changes.

### Modified Capabilities

None.

## Impact

- Affects inventory reservation persistence and SQL, Kafka consumers/producers, outbox relays, and integration tests in both application services.
- Adds Flyway migrations for both service databases, including a baseline path for initialized schemas, reservation/outbox tables, and `ddl-auto: validate`.
- Adds DLT telemetry and a Datadog monitor definition using the repository's existing manual monitor import process.
- Does not change gateway authentication, host port exposure, management endpoints, client request idempotency, product pricing, DLT replay tooling, or malformed-message deserialization and topic partition configuration.
