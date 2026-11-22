## ADDED Requirements

### Requirement: Transition an order only from pending
The order service SHALL transition an order from `PENDENTE` to `CONFIRMADO` or `CANCELADO` only once. An event that repeats the current terminal outcome SHALL be an idempotent no-op. An event that conflicts with an already terminal state SHALL NOT change the state.

#### Scenario: A pending order receives a successful inventory outcome
- **WHEN** a `PENDENTE` order receives `EstoqueReservadoEvent`
- **THEN** the order transitions to `CONFIRMADO`

#### Scenario: A pending order receives a failed inventory outcome
- **WHEN** a `PENDENTE` order receives `EstoqueIndisponivelEvent`
- **THEN** the order transitions to `CANCELADO`

#### Scenario: A duplicate matching outcome is delivered
- **WHEN** an order already in a terminal state receives the same outcome again
- **THEN** the order state and timestamps remain unchanged
- **AND** no second outcome metric is recorded

#### Scenario: A conflicting terminal outcome is delivered
- **WHEN** an order already in a terminal state receives the opposite outcome
- **THEN** the order state remains unchanged
- **AND** the service records the conflict for observability without counting a transition

### Requirement: Count only effective order transitions
The order service SHALL increment `pedido.confirmado` or `pedido.cancelado` and record saga duration only when the order is effectively transitioned from `PENDENTE` to the corresponding terminal state.

#### Scenario: A transition is applied
- **WHEN** a pending order is changed to a terminal state
- **THEN** exactly one matching outcome counter and saga duration sample are recorded

#### Scenario: An event causes no state change
- **WHEN** a duplicate or conflicting event does not change the order state
- **THEN** no outcome counter or saga duration sample is recorded
