## ADDED Requirements

### Requirement: Persist outgoing events with the business transaction
The order and inventory services SHALL persist each outgoing event in a transactional outbox in the same database transaction as the business state that caused it. An outbox relay SHALL publish only committed events and SHALL mark an event published only after the broker acknowledges it.

#### Scenario: An order transaction commits
- **WHEN** an order is persisted successfully
- **THEN** its `PedidoCriadoEvent` outbox record is committed with the order
- **AND** the relay can publish the event after the commit

#### Scenario: An order transaction rolls back
- **WHEN** the order transaction fails to commit
- **THEN** neither the order nor its outbox event is visible for publication

#### Scenario: Kafka is unavailable during publication
- **WHEN** a relay cannot publish an outbox event or receive broker acknowledgement
- **THEN** the outbox record remains eligible for retry
- **AND** the relay does not mark the event published

### Requirement: Make event processing safe for at-least-once delivery
Consumers SHALL apply a business event and persist any resulting outbox event atomically. Repeated delivery of the same event SHALL NOT repeat its business effect. Outbox publication MAY produce duplicate Kafka records if broker acknowledgement succeeds but marking the outbox record fails; consumers SHALL remain safe in this case.

#### Scenario: A successful reservation outcome is committed
- **WHEN** the inventory service finishes processing an order
- **THEN** the reservation result and its `EstoqueReservadoEvent` or `EstoqueIndisponivelEvent` outbox record are committed atomically

#### Scenario: A consumer receives an event more than once
- **WHEN** a previously processed event is delivered again
- **THEN** the consumer does not repeat the business effect
- **AND** it does not create an additional logical outcome

### Requirement: Retry transient processing failures and retain exhausted records
Consumers SHALL retry transient processing failures according to the configured retry policy. When retries are exhausted, the record SHALL be retained in the associated dead-letter topic and SHALL NOT cause an automatic order state transition.

#### Scenario: Retry attempts are exhausted
- **WHEN** a transient failure persists through all configured attempts
- **THEN** the event is published to its dead-letter topic
- **AND** the order remains in its existing state until an operator reprocesses the event

### Requirement: Expose dead-letter activity
When a record reaches a dead-letter topic, the service SHALL emit a structured error log and a low-cardinality metric identifying the service and source topic. The repository SHALL provide a Datadog monitor definition that alerts when dead-letter activity occurs.

#### Scenario: An event reaches a dead-letter topic
- **WHEN** the retry policy sends a record to a dead-letter topic
- **THEN** the service emits a log with source topic, DLT topic, key, partition, offset, and failure cause
- **AND** increments the dead-letter metric
- **AND** the Datadog monitor can alert on the metric

### Requirement: Retry outcome events for an order that is not visible
The order service SHALL treat an outcome event for an order that is not currently visible as retryable instead of acknowledging and discarding it. If retries are exhausted, the event SHALL follow the dead-letter behavior and SHALL NOT transition an order automatically.

#### Scenario: An outcome event references a missing order
- **WHEN** an inventory outcome references an order that cannot be found
- **THEN** the consumer raises a retryable processing failure
- **AND** does not acknowledge the event as successfully processed
