# Project notes

## Workflow

- Branches: `main` e `develop` são protegidas por ruleset (PR obrigatório, check `Maven verify`).
  Toda mudança entra por branch própria com PR para `develop`; `develop` é promovida para `main`
  pelo PR de promoção. Merge por *merge commit* (na `main`) ou *merge commit*/*rebase* (na `develop`),
  nunca *squash*, para as duas branches não divergirem.
- Mudanças seguem o padrão OpenSpec (`openspec/changes/<nome>/` com proposal, design, specs e tasks;
  arquivadas em `openspec/changes/archive/` com a data, e specs consolidadas em `openspec/specs/`).
- Cadastro de usuários concluído na change `keycloak-user-registration` (arquivada em
  2026-11-27): gateway expõe `POST /api/usuarios` sem JWT, com rate limit por IP, e o
  `user-registration-service` cria contas no realm via Admin API com client credentials.
- Segurança de API concluída na change `api-security-keycloak` (arquivada em 2026-11-25):
  gateway valida JWTs do Keycloak (issuer, audience, assinatura e validade); os serviços
  pedido/estoque não publicam portas no host; Actuator usa listeners de gerenciamento
  separados, sem publicação no host.
- Keycloak local: realm `pedido-estoque`, issuer padrão
  `http://localhost:8180/realms/pedido-estoque`, audience `pedido-estoque-api`.
  Login local é feito pelo fluxo de usuário documentado nos READMEs da aplicação e da
  infraestrutura. Cadastro público em `POST /api/usuarios` cria a conta no realm via
  `user-registration-service` com client credentials de menor privilégio (role
  `realm-management.manage-users` e scope `kc-admin-api`). Ajustes de
  issuer, JWKS e audience são configuráveis por ambiente. Não versionar credenciais
  locais; defaults de desenvolvimento não devem ser usados em produção.
- Ao alterar execução local, cadastro/login ou componentes, atualize na mesma change
  `docs/EXECUCAO-LOCAL.md` (passo a passo e comandos de validação), `insomnia/README.md`
  e `docs/arquitetura.md` (diagramas Mermaid); o README raiz acompanha mudanças de
  módulos ou de fluxo. Não deixar documentação de fluxo para depois de arquivar a change.

## Follow-up backlog

Itens planejados, cada um para virar uma change separada. Estado verificado contra o código da
`develop` em 22/11/2026; todos continuam pendentes. Os itens 1 a 5 vieram da revisão de código e
ficaram fora da change `harden-order-kafka-consistency`, já arquivada.

1. **API security — concluído:** validação JWT real no gateway via Keycloak, serviços de
   pedido/estoque privados à rede Docker e endpoints Actuator restritos em listeners de
   gerenciamento isolados. Requisitos consolidados em `openspec/specs/`.
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
5. **Kafka topology:** explicitly manage topic creation, partitions, and related scaling
   policy in a separate change. (A parte de mensagens malformadas, com
   `ErrorHandlingDeserializer` e desvio para o DLT, já foi concluída em change arquivada.)
6. **Datadog artifacts out of date:** the dashboard widget "compensações e conflitos de lock" and the
   monitor `monitors/conflito-lock-estoque.json` query `estoque.compensacao` and
   `estoque.lock.conflito`, metrics removed with the atomic reservation. Remove or replace them,
   and consider widgets/monitors for the new metrics (`estoque.reserva.idempotency.conflict`,
   `pedido.transicao.conflito`, `pedido.evento.pedido_nao_visivel`) and a "stuck saga" monitor.

## Other open ideas (from the README checklist)

Cache Redis para leitura, múltiplas réplicas dos serviços, réplica de leitura do Postgres e
monitor de "saga travada".
