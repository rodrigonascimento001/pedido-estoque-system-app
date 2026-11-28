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
- Idioma: código novo (classes, colunas, métricas, headers, roles) usa inglês; nomes existentes em
  português ficam como estão até uma change dedicada.
- Identidade nos serviços: o gateway repassa o `sub` do JWT validado no header interno
  `X-Authenticated-User` (constante em `common`) e descarta o valor enviado pelo cliente. O
  `pedido-service` confia nesse header, o que só é seguro enquanto as portas 8081/8082 não forem
  publicadas no host.
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

Itens planejados, cada um para virar uma change OpenSpec separada. Estado verificado contra o
código da `develop` em 28/11/2026. Concluídos e arquivados: consistência Kafka (outbox, reserva
idempotente, transições guardadas), mensagens malformadas, segurança de API com Keycloak,
cadastro público de usuários e idempotência do `POST /api/pedidos` (`Idempotency-Key`).

### A. Próximas changes (ordem sugerida)

1. **Autorização (novo):** o gateway só autentica. Hoje qualquer usuário logado lê o pedido de
   outro e altera estoque. Associar o pedido ao usuário (`sub` do JWT), filtrar leituras por dono
   e restringir `/api/estoque/**` a uma role. Fazer antes ou junto do item 2.
2. **Preço no servidor:** `precoUnitario` ainda vem do cliente em `PedidoRequest`. Resolver o preço
   no servidor a partir do catálogo do serviço de produtos (item C1) e remover o campo da
   requisição. Se o item C1 ainda não existir, o primeiro passo é decidir entre catálogo local no
   estoque-service ou o serviço novo.
3. **Pedido preso em PENDENTE e saga travada (novo):** se o estoque nunca responde, ou o registro
   fica no DLT, o pedido fica PENDENTE para sempre. Definir prazo, cancelamento/compensação e um
   monitor de "saga travada". Junta parte do item 4.
4. **Datadog desatualizado:** o widget "compensações e conflitos de lock" e o monitor
   `monitors/conflito-lock-estoque.json` consultam `estoque.compensacao` e `estoque.lock.conflito`,
   métricas removidas. Remover ou trocar, e cobrir as métricas novas
   (`estoque.reserva.idempotency.conflict`, `pedido.transicao.conflito`,
   `pedido.evento.pedido_nao_visivel`, `kafka.poison.records`). Combina com o item 3.
5. **Replay do DLT:** ferramenta (CLI ou endpoint administrativo) para inspecionar e reprocessar
   registros dos tópicos `-dlt`. Alerta e métrica já existem (`@DltHandler`, `kafka.dlt.records`,
   monitor `dlt-kafka`).
6. **Topologia do Kafka:** criar tópicos, partições e réplicas de forma explícita, inclusive
   `-retry-N` e `-dlt`. Só ganha peso com mais de uma réplica dos serviços.

### B. Keycloak: pontos a refinar

Surgiram durante a change de segurança e ainda precisam de definição antes de virarem changes.

- **Segredos e defaults de desenvolvimento:** o client do `user-registration-service` e o realm
  local usam credenciais de dev. Mover o segredo para o Secrets Manager com falha rápida, como já
  é feito com a senha do banco, e documentar o que muda em produção.
- **Teste ponta a ponta com Keycloak real:** os testes do gateway usam decoder simulado. Avaliar
  Testcontainers do Keycloak cobrindo cadastro, login e criação de pedido. Decidir o custo no CI.
- **Roles e modelo de permissões:** definir quais roles existem (cliente, operador, admin) e onde
  são lidas no token. Entrada do item A1.
- **Ciclo de vida da conta:** verificação de e-mail, recuperação de senha e desativação. Decidir
  se ficam no Keycloak ou no serviço de usuários (item C2).

### C. Novos serviços

1. **Serviço de produtos (catálogo):** cadastro e consulta de produtos, com preço, e dono do preço
   oficial usado pelo pedido. Converge com o item A2. Pontos a decidir: banco próprio (padrão do
   projeto), como o pedido consulta o preço (chamada síncrona via REST ou cópia local por evento),
   e a relação com o saldo, que continua no estoque-service. Sugestão: o `produtoId` é a chave
   comum, e o saldo permanece no estoque.
2. **Serviço de usuários (perfil):** dados cadastrais, e-mail, telefone e endereços de entrega,
   separado do `user-registration-service` (que só cria a conta no Keycloak). O `sub` do JWT é o
   vínculo. Objetivo de evolução: o pedido referenciar o usuário e o endereço de entrega, e
   habilitar disparo de e-mail. Pontos a decidir: se o pedido guarda um instantâneo do endereço
   (recomendado, para não mudar pedidos antigos), e se a criação de perfil acontece no cadastro,
   por evento, ou no primeiro uso.
3. **Notificações por e-mail (decorrência do C2):** consumir eventos do pedido (criado,
   confirmado, cancelado) e enviar e-mail. Fica depois de C2 e do item A3. Cuidados: idempotência
   por evento e tratamento de falha no provedor, com retry e DLT como nos demais consumers.

Cada serviço novo segue o padrão atual: módulo Maven, banco Postgres próprio, rota no gateway com
JWT e rate limit, métricas no Datadog, imagem no GHCR e entrada em `docs/arquitetura.md`.

### D. Evolução de plataforma

- Cache Redis para leitura de pedido, produto e estoque.
- Múltiplas réplicas dos serviços (exige o item A6 e testes de concorrência entre réplicas).
- Réplica de leitura do Postgres.
- `open-promote-pr.yml` comparar o conteúdo das árvores em vez de contar commits, para evitar o
  ciclo de back-merge da `main` para a `develop`.
- Observabilidade distribuída: propagar o `traceparent` nos eventos Kafka para ligar os spans de
  pedido e estoque no mesmo trace.
