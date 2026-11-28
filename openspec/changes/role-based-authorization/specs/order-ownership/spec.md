## ADDED Requirements

### Requirement: Orders record their owner
The order service SHALL store the authenticated subject received in `X-Authenticated-User` as the owner of every order it creates. Creating an order without that header SHALL fail with `400 Bad Request` and SHALL NOT create the order. Orders that existed before this capability SHALL have no owner.

#### Scenario: Owner is recorded on creation
- **WHEN** an authenticated customer creates an order
- **THEN** the stored order has that customer's subject as its owner

#### Scenario: Creation without a user is rejected
- **WHEN** a create request reaches the order service without `X-Authenticated-User`
- **THEN** the service returns `400` and creates no order

### Requirement: Orders are visible only to their owner or an operator
`GET /api/pedidos/{id}` SHALL return the order when the caller's subject is the owner or the caller's roles include `operator`. In every other case, including orders without an owner for non-operators, the service SHALL respond `404 Not Found` with the same body as for an order that does not exist.

#### Scenario: Owner reads the order
- **WHEN** the owner requests their order
- **THEN** the service returns `200` with the order

#### Scenario: Another customer cannot read it
- **WHEN** a different customer requests the order id
- **THEN** the service returns `404`, indistinguishable from an unknown id
- **AND** `order.access.denied` increases by one

#### Scenario: Operator reads any order
- **WHEN** a caller with `operator` requests an order owned by someone else
- **THEN** the service returns `200` with the order

#### Scenario: Legacy order without an owner
- **WHEN** a customer requests an order that has no owner
- **THEN** the service returns `404`
- **AND** an operator receives `200`

### Requirement: Ownership does not alter the order flow
Ownership SHALL NOT change order statuses, Kafka events, stock reservation or idempotency behavior.

#### Scenario: Saga unchanged
- **WHEN** an owned order is created
- **THEN** the `pedido-criado` event, the reservation and the status transitions behave as before
