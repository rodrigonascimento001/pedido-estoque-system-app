## ADDED Requirements

### Requirement: Order creation honors an idempotency key
The system SHALL accept an optional `Idempotency-Key` header on `POST /api/pedidos`. When a request carries a key that was not used before by the same user within the retention window, the system SHALL create the order as usual and record the key with the resulting order. When the header is absent, the system SHALL behave as it does today.

#### Scenario: First request with a key creates the order
- **WHEN** an authenticated user submits a valid order with a new `Idempotency-Key`
- **THEN** the system creates one order in `PENDENTE`, returns `201 Created`, and records the key linked to that order

#### Scenario: Request without a key is unchanged
- **WHEN** a user submits a valid order without the header
- **THEN** the system creates a new order on every call, exactly as before

#### Scenario: Failed creation does not consume the key
- **WHEN** order creation fails and its transaction rolls back
- **THEN** no key record remains and the client can retry with the same key

### Requirement: A repeated request returns the original order
When the same user sends the same `Idempotency-Key` with an equivalent request body within the retention window, the system SHALL return the order created by the first request and SHALL NOT create another order, outbox event, or stock reservation.

#### Scenario: Retry after a lost response
- **WHEN** a user repeats a request whose key was already recorded and the body is identical
- **THEN** the system returns `201 Created` with the same `Location` and the current representation of the original order
- **AND** the response carries `Idempotent-Replayed: true`
- **AND** the number of orders and `pedido-criado` outbox events does not increase

#### Scenario: Concurrent requests with the same key
- **WHEN** two requests with the same user and key arrive at the same time
- **THEN** exactly one order is created and both callers receive that order

### Requirement: Reusing a key with a different body is rejected
The system SHALL compare a hash of the request body with the one recorded for the key and SHALL reject a mismatch without creating an order.

#### Scenario: Same key, different body
- **WHEN** a user sends a recorded key with a body whose hash differs
- **THEN** the system returns `422 Unprocessable Entity` with a safe error message
- **AND** no order is created and the counter `pedido.idempotency.conflict` is incremented

### Requirement: Keys are scoped to the authenticated user
The system SHALL scope every key to the authenticated user's subject. The gateway SHALL pass the validated subject to the order service in an internal header and SHALL discard any header of that name sent by the client. The order service SHALL reject a request that carries a key but no subject.

#### Scenario: Two users use the same key value
- **WHEN** two different users send the same `Idempotency-Key` value
- **THEN** each user gets their own order and neither receives the other's order

#### Scenario: Client forges the internal header
- **WHEN** a client sends the internal subject header through the gateway
- **THEN** the gateway replaces it with the subject from the validated JWT

#### Scenario: Key without a subject
- **WHEN** a request with `Idempotency-Key` reaches the order service without the internal subject header
- **THEN** the service returns `400 Bad Request` and creates no order

### Requirement: Keys have a valid format
The system SHALL accept keys of 1 to 128 printable ASCII characters without whitespace and SHALL reject any other value with `400 Bad Request`.

#### Scenario: Invalid key
- **WHEN** the header is empty, longer than 128 characters, or contains whitespace or non-ASCII characters
- **THEN** the system returns `400 Bad Request` and creates no order

### Requirement: Keys expire
The system SHALL keep a key for a configurable retention period, 24 hours by default, treat an expired key as unused, and remove expired records periodically.

#### Scenario: Key reused after expiry
- **WHEN** a user sends a key whose record is older than the retention period
- **THEN** the system creates a new order and records the key again

#### Scenario: Expired records are cleaned up
- **WHEN** the cleanup job runs
- **THEN** records past their expiry are deleted and unexpired records are kept

### Requirement: Idempotency activity is observable without leaking keys
The system SHALL count replays in `pedido.idempotency.replay` and key/body conflicts in `pedido.idempotency.conflict`, and SHALL NOT use the key or the user as a metric tag or write the key value to logs.

#### Scenario: Replay is counted
- **WHEN** a repeated request returns the original order
- **THEN** `pedido.idempotency.replay` increases by one and the log line carries the order id but not the key
