## 1. Database migrations and persistence model

- [x] 1.1 Add Flyway dependencies and version 1 schema migrations for the current `pedido-service` and `estoque-service` databases.
- [x] 1.2 Configure baseline-on-migrate at version 1 and verify it preserves initialized local database rows while fresh databases are created from migrations.
- [x] 1.3 Add migrations for reservation headers/details and outbox tables, including unique constraints for `pedido_id` and `(pedido_id, produto_id)`.
- [x] 1.4 Change both services to `ddl-auto: validate` and verify startup against fresh and baselined schemas.

## 2. Atomic and idempotent inventory reservation

- [x] 2.1 Normalize order items by summing positive quantities per product and calculate a stable fingerprint from sorted product IDs and quantities.
- [x] 2.2 Add repository operations to lock stock rows in stable order and apply conditional atomic decrements while incrementing the existing version column.
- [x] 2.3 Implement one transaction that persists the reservation header, per-product details, stock changes, and success/rejection outbox outcome together.
- [x] 2.4 Return the stored outcome for duplicate order IDs and reject a different normalized payload for an existing order ID without changing stock.
- [x] 2.5 Remove per-item transaction/retry/compensation behavior and classify business rejection separately from retryable technical failures.

## 3. Transactional outbox and relay

- [x] 3.1 Replace direct `PedidoCriadoEvent` publication with an outbox record written with the committed order.
- [x] 3.2 Replace direct inventory outcome publication with an outbox record written with the reservation result.
- [x] 3.3 Implement bounded, leased outbox claiming with `FOR UPDATE SKIP LOCKED`, broker-acknowledged publication, retry metadata, and stable event IDs in both services.
- [x] 3.4 Ensure duplicate broker publication after a relay crash is safe through inventory reservation uniqueness and guarded order transitions.

## 4. Retry, DLT, and event consumers

- [x] 4.1 Configure retry/backoff for technical failures on all flow consumers while allowing business rejection outcomes to complete normally.
- [x] 4.2 Change missing-order outcome handling to throw a retryable exception instead of acknowledging and discarding the event.
- [x] 4.3 Add DLT handlers that log source/DLT topic, key, partition, offset, and cause without logging payload contents.
- [x] 4.4 Emit the low-cardinality `kafka.dlt.records` metric and add a Datadog DLT alert monitor definition.
- [x] 4.5 Document that DLT records remain available for manual operational reprocessing and that this change does not add an automatic replay tool.

## 5. Guarded state transitions and metrics

- [x] 5.1 Make `Pedido.confirmar()` and `Pedido.cancelar()` change state only from `PENDENTE` and report whether a transition occurred.
- [x] 5.2 Treat repeated matching outcomes as no-ops and conflicting terminal outcomes as observable no-ops.
- [x] 5.3 Increment outcome counters and record saga duration only when a state transition was applied.

## 6. Integration and migration coverage

- [x] 6.1 Add integration tests proving a failed later item leaves all stock unchanged and persists one rejected reservation outcome.
- [x] 6.2 Add tests for repeated product lines, duplicate success/rejection delivery, and same-order/different-payload conflicts.
- [x] 6.3 Add concurrent reservation tests proving stock never becomes negative and reservations are all-or-nothing.
- [x] 6.4 Add outbox tests for transaction rollback, broker failure retry, acknowledgement, and duplicate publication after relay recovery.
- [x] 6.5 Add consumer tests for missing-order retry, DLT telemetry, duplicate/conflicting outcomes, and transition-only metrics.
- [x] 6.6 Add Flyway tests for fresh databases and existing initialized databases with data preservation.
- [x] 6.7 Run `mvn -B verify` and validate the OpenSpec change before marking the change ready for implementation review.
