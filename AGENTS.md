# Project notes

## Follow-up backlog

The following review items are intentionally outside the active change
`harden-order-kafka-consistency`. Pick them up in separate changes after the
order and inventory consistency work is complete:

1. **API security:** replace the gateway's Bearer-presence check with real JWT
   validation; keep application services unreachable directly from the host;
   restrict actuator exposure and isolate the management port. Confirm the JWT
   issuer/key source when the security change is proposed.
2. **Server-side pricing:** stop accepting `precoUnitario` as authoritative
   client input. Resolve prices from a server-controlled product catalog. The
   project currently has no catalog, so decide whether to add one locally or
   integrate an existing catalog before implementation.
3. **HTTP request idempotency:** add `Idempotency-Key` support to
   `POST /api/pedidos` so a client retry cannot create a second order. The
   active consistency change handles duplicate Kafka events for the same
   `pedidoId`; it does not deduplicate separate HTTP requests.
4. **DLT replay tool:** provide an operator-facing UI or command-line tool to
   inspect and replay dead-letter records. The active change adds DLT
   observability and alerts, while replay remains an operational follow-up.
5. **Kafka poison messages and topology:** configure deserialization error
   handling for malformed payloads and explicitly manage topic creation,
   partitions, and related scaling policy in a separate change.

These items are planned next steps, not requirements for the active change.
