# idempotent-inventory-reservation Specification

## Purpose
TBD - created by archiving change harden-order-kafka-consistency. Update Purpose after archive.
## Requirements
### Requirement: Reserve an order atomically
The inventory service SHALL treat all normalized product quantities in one order as a single reservation operation. A successful reservation SHALL decrement every requested product quantity in one database transaction. A missing product or insufficient balance SHALL reject the complete reservation without decrementing any product.

#### Scenario: All products have sufficient stock
- **WHEN** the inventory service processes an order whose normalized items all have sufficient stock
- **THEN** it decrements every item and records one successful reservation in the same transaction

#### Scenario: A later item is unavailable
- **WHEN** at least one normalized item has no product row or insufficient stock
- **THEN** no requested product quantity is decremented
- **AND** the service records one rejected reservation and its failure result

### Requirement: Aggregate repeated product lines
The inventory service SHALL aggregate quantities for repeated `produtoId` values before checking or changing stock. It SHALL persist no more than one reservation detail for each `(pedidoId, produtoId)` pair.

#### Scenario: An order repeats the same product
- **WHEN** an order contains multiple lines for the same product
- **THEN** the service checks and reserves their summed quantity once
- **AND** it records one reservation detail for that order and product

### Requirement: Make reservation processing idempotent by order
The inventory service SHALL enforce a unique reservation identity per `pedidoId`. Reprocessing the same order payload SHALL return the persisted reservation outcome without changing stock or creating a second outcome event. Reusing a `pedidoId` with a different normalized payload SHALL be rejected as an idempotency conflict without changing stock.

#### Scenario: Kafka redelivers an already successful order
- **WHEN** the inventory service receives the same order ID and normalized payload again
- **THEN** it returns the persisted successful outcome
- **AND** it does not decrement stock again

#### Scenario: Kafka redelivers an already rejected order
- **WHEN** the inventory service receives the same order ID and normalized payload after a business rejection
- **THEN** it returns the persisted rejection outcome
- **AND** it does not reevaluate stock or create a second outcome event

#### Scenario: An order ID is reused with different contents
- **WHEN** the inventory service receives an existing order ID with a different normalized payload
- **THEN** it records an idempotency conflict
- **AND** it leaves stock and the persisted reservation outcome unchanged

### Requirement: Prevent overselling under concurrent reservations
The inventory service SHALL update stock using a conditional atomic decrement that succeeds only when the available quantity is at least the requested quantity. Concurrent reservation transactions SHALL NOT reduce stock below zero.

#### Scenario: Two orders compete for the remaining stock
- **WHEN** concurrent orders request more stock than is available in total
- **THEN** no more than the available quantity is reserved
- **AND** each order receives a complete success or rejection outcome without a partial reservation

