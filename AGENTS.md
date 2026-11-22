# Project notes

## Workflow

- Branches: `main` e `develop` são protegidas por ruleset (PR obrigatório, check `Maven verify`).
  Toda mudança entra por branch própria com PR para `develop`; `develop` é promovida para `main`
  pelo PR de promoção. Merge por *merge commit* (na `main`) ou *merge commit*/*rebase* (na `develop`),
  nunca *squash*, para as duas branches não divergirem.
- Mudanças seguem o padrão OpenSpec (`openspec/changes/<nome>/` com proposal, design, specs e tasks;
  arquivadas em `openspec/changes/archive/` com a data, e specs consolidadas em `openspec/specs/`).
- Não há change ativa no momento.

## Follow-up backlog

Itens planejados, cada um para virar uma change separada. Estado verificado contra o código da
`develop` em 22/11/2026; todos continuam pendentes. Os itens 1 a 5 vieram da revisão de código e
ficaram fora da change `harden-order-kafka-consistency`, já arquivada.

1. **API security:** replace the gateway's Bearer-presence check with real JWT
   validation (hoje o `AuthenticationFilter` só confere o prefixo `Bearer `); keep application
   services unreachable directly from the host (as portas 8081 e 8082 estão publicadas no
   `docker-compose.yml`); restrict actuator exposure and isolate the management port. Confirm the
   JWT issuer/key source when the security change is proposed.
2. **Server-side pricing:** stop accepting `precoUnitario` as authoritative
   client input (hoje vem em `PedidoRequest`). Resolve prices from a server-controlled product
   catalog. The project currently has no catalog, so decide whether to add one locally or
   integrate an existing catalog before implementation.
3. **HTTP request idempotency:** add `Idempotency-Key` support to
   `POST /api/pedidos` so a client retry cannot create a second order. The
   consistency change handles duplicate Kafka events for the same
   `pedidoId`; it does not deduplicate separate HTTP requests.
4. **DLT replay tool:** provide an operator-facing UI or command-line tool to
   inspect and replay dead-letter records. DLT observability and alerts exist
   (`@DltHandler`, métrica `kafka.dlt.records`, monitor `dlt-kafka`); replay remains an operational follow-up.
5. **Kafka poison messages and topology:** configure deserialization error
   handling for malformed payloads (os consumers usam `JsonDeserializer` direto, sem
   `ErrorHandlingDeserializer`) and explicitly manage topic creation,
   partitions, and related scaling policy in a separate change.
6. **Datadog artifacts out of date:** the dashboard widget "compensações e conflitos de lock" and the
   monitor `monitors/conflito-lock-estoque.json` query `estoque.compensacao` and
   `estoque.lock.conflito`, metrics removed with the atomic reservation. Remove or replace them,
   and consider widgets/monitors for the new metrics (`estoque.reserva.idempotency.conflict`,
   `pedido.transicao.conflito`, `pedido.evento.pedido_nao_visivel`) and a "stuck saga" monitor.

## Other open ideas (from the README checklist)

Autenticação real no gateway (item 1 acima), cache Redis para leitura, múltiplas réplicas dos
serviços, réplica de leitura do Postgres e monitor de "saga travada".
