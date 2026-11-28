# Arquitetura do sistema Pedido-Estoque

Estado após as changes `api-security-keycloak` e `keycloak-user-registration`. Os diagramas são Mermaid e o GitHub os renderiza nesta página. Atualize-os na mesma change que alterar fluxo, tópicos ou componentes; quando o passo a passo local mudar, acompanhe também `docs/EXECUCAO-LOCAL.md`.

## 1. Componentes e dados

Cada serviço tem seu próprio Postgres. A única ligação entre pedido e estoque é o Kafka, e todo evento sai por uma tabela outbox gravada na mesma transação do negócio.

```mermaid
flowchart LR
  C([Cliente / navegador]) -->|"login Authorization Code + PKCE :8180"| KC
  KC -->|"access token assinado"| C
  C -->|"POST /api/usuarios, sem token :8080"| GW
  C -->|"Bearer access token :8080"| GW
  subgraph GWB["api-gateway :8080 / management :9080"]
    GW["Spring Security Resource Server<br/>valida JWT exceto POST /api/usuarios"] --> RL["RequestRateLimiter por IP<br/>geral: 20 req/s, burst 40<br/>cadastro: 5 req/s, burst 10"]
  end
  RL <-->|"contadores"| R[("Redis")]
  RL -->|"/api/pedidos/**"| PS
  RL -->|"/api/estoque/**"| ES
  subgraph INFRA["Compose de infraestrutura / rede compartilhada"]
    KC["Keycloak :8080 interno / :8180 host<br/>realm pedido-estoque"]
  end
  subgraph RSB["user-registration-service :8083 interno"]
    RS["RegistrationController<br/>UserRegistrationService<br/>client credentials do cliente técnico"]
  end
  RL -->|"/api/usuarios"| RS
  RS -->|"Admin API: cria usuário habilitado"| KC
  KC -.->|"token do cliente técnico"| RS
  KC -.->|"JWKS / chaves de assinatura"| GW
  subgraph PSB["pedido-service :8081"]
    PS["PedidoController<br/>PedidoService"]
    PO["OutboxRelay<br/>a cada 1s, lote 25"]
    PC["EstoqueEventConsumer<br/>group pedido-service"]
  end
  subgraph ESB["estoque-service :8082"]
    ES["EstoqueController<br/>EstoqueService"]
    EC["PedidoEventConsumer<br/>ReservaPedidoProcessor"]
    EO["OutboxRelay<br/>a cada 1s, lote 25"]
  end
  PS --> PDB[("Postgres pedido<br/>pedidos, pedido_itens,<br/>outbox_event")]
  PO --> PDB
  PC --> PDB
  ES --> EDB[("Postgres estoque<br/>estoque, reserva_pedido,<br/>reserva_pedido_item,<br/>outbox_event")]
  EC --> EDB
  EO --> EDB
  PO -->|"publica"| T1{{"pedido-criado"}}
  T1 -->|"consome"| EC
  EO -->|"publica"| T2{{"estoque-reservado"}}
  EO -->|"publica"| T3{{"estoque-indisponivel"}}
  T2 -->|"consome"| PC
  T3 -->|"consome"| PC
  T1 -.->|"falha"| D1{{"pedido-criado-retry-N<br/>pedido-criado-dlt"}}
  T2 -.->|"falha"| D2{{"estoque-reservado-retry-N<br/>-dlt"}}
  T3 -.->|"falha"| D3{{"estoque-indisponivel-retry-N<br/>-dlt"}}
  DD["Datadog<br/>agent, StatsD, logs JSON"] -.- PSB
  DD -.- ESB
```

_O navegador pode criar a conta antes de ter token: `POST /api/usuarios` é o único endpoint de API anônimo e é rate-limitado separadamente. O `user-registration-service` usa o cliente confidencial `pedido-estoque-user-registration` (permissão `manage-users`) para chamar o Admin API; senha, token de serviço e client secret não voltam na resposta nem entram em logs/métricas/traces. Depois do cadastro, o browser autentica no Keycloak (Authorization Code + PKCE) e recebe um access token. O cliente envia o token ao gateway; o gateway valida assinatura usando o JWKS do Keycloak, `iss`, `aud` e validade temporal antes de aplicar rate limit e rotear a chamada. Não há exigência de roles/scopes nesta etapa. Linhas cheias são o caminho normal; linhas tracejadas indicam consulta de chaves/token técnico ou desvio para retry/DLT. Postgres, Kafka, Redis e Keycloak ficam no Compose de infraestrutura, separado do Compose da aplicação. Apenas gateway (`8080`) e Keycloak (`8180`) são publicados no host; cadastro, pedido, estoque e management permanecem internos._

## 2. Cadastro de usuário e login

Uma conta precisa existir no realm antes do login. O cadastro é público no gateway, mas a criação no Keycloak usa uma identidade técnica isolada, com permissão apenas de gerenciar usuários. A senha enviada pelo cliente é *write-only*: não é retornada, persistida pela aplicação nem registrada em logs, métricas ou traces.

```mermaid
sequenceDiagram
  autonumber
  actor C as Cliente
  participant GW as api-gateway
  participant RS as user-registration-service
  participant KC as Keycloak (realm pedido-estoque)

  C->>GW: POST /api/usuarios (sem Authorization)
  GW->>GW: rate limit por IP (5 req/s, burst 10)
  GW->>RS: encaminha pela rede Docker
  RS->>RS: valida username, email e senha
  RS->>KC: POST /token (client credentials do cliente técnico)
  KC-->>RS: access token do Admin API
  RS->>KC: POST /admin/realms/pedido-estoque/users (enabled, senha não temporária)
  alt conta criada
    KC-->>RS: 201 + Location com o id
    RS-->>C: 201 {id, username}, sem senha
  else username/e-mail duplicado
    KC-->>RS: 409
    RS-->>C: 409 user_already_exists
  else dado ou senha inválidos
    KC-->>RS: 400/422
    RS-->>C: 400 invalid_registration
  else Admin API indisponível
    RS-->>C: 503 identity_provider_unavailable
  end
  C->>KC: login Authorization Code + PKCE com a nova conta
  KC-->>C: access token com audience pedido-estoque-api
  C->>GW: rota de negócio protegida + Bearer token
```

_A conta começa habilitada e sem roles adicionais; e-mail verificado, recuperação e MFA ficam fora desta change. A única operação anônima da API é o `POST /api/usuarios`; os demais métodos e caminhos continuam exigindo JWT. O passo a passo com curl e Insomnia está em `docs/EXECUCAO-LOCAL.md`._

## 3. Criar pedido com estoque disponível

O cliente recebe 201 assim que o pedido é gravado como PENDENTE. A confirmação chega depois, de forma assíncrona. Nenhuma publicação no Kafka acontece dentro da transação HTTP.

```mermaid
sequenceDiagram
  autonumber
  actor C as Cliente
  participant KC as Keycloak
  participant GW as api-gateway
  participant PS as pedido-service
  participant PDB as Postgres pedido
  participant K as Kafka
  participant ES as estoque-service
  participant EDB as Postgres estoque

  C->>KC: login do usuário (Authorization Code + PKCE)
  KC-->>C: access token (issuer e audience configurados)
  C->>GW: POST /api/pedidos + Bearer token
  GW->>GW: valida assinatura, issuer, audience e validade do JWT
  GW->>KC: busca JWKS (quando necessário, pela rede Docker)
  KC-->>GW: chaves públicas do realm
  GW->>GW: aplica limite por IP no Redis
  GW->>PS: encaminha
  rect rgba(120,150,200,0.15)
  Note over PS,PDB: uma transação
  PS->>PDB: INSERT pedido PENDENTE + itens
  PS->>PDB: INSERT outbox_event PedidoCriadoEvent
  end
  PS-->>C: 201 PENDENTE

  loop a cada 1s
    PS->>PDB: reivindica eventos pendentes
    PS->>K: publica pedido-criado, chave pedidoId
    PS->>PDB: marca evento como publicado
  end

  K->>ES: pedido-criado
  rect rgba(120,150,200,0.15)
  Note over ES,EDB: uma transação, idempotente por pedidoId
  ES->>EDB: existe reserva_pedido para o pedido?
  ES->>EDB: baixa saldo de todos os itens, ou de nenhum
  ES->>EDB: grava reserva_pedido e itens
  ES->>EDB: INSERT outbox_event EstoqueReservadoEvent
  end

  loop a cada 1s
    ES->>EDB: reivindica eventos pendentes
    ES->>K: publica estoque-reservado
  end

  K->>PS: estoque-reservado
  PS->>PDB: UPDATE pedido para CONFIRMADO somente se PENDENTE

  C->>GW: GET /api/pedidos/{id}
  GW->>PS: encaminha
  PS-->>C: 200 CONFIRMADO
```

_As duas caixas sombreadas são as fronteiras transacionais. Se o processo cair entre gravar e publicar, o relay publica no próximo ciclo (entrega pelo menos uma vez)._

## 4. Estoque insuficiente

A reserva é tudo ou nada. Se um item não tem saldo, nada é baixado, o resultado negativo é gravado e o pedido é cancelado.

```mermaid
sequenceDiagram
  autonumber
  participant PS as pedido-service
  participant K as Kafka
  participant ES as estoque-service
  participant EDB as Postgres estoque
  participant PDB as Postgres pedido

  K->>ES: pedido-criado
  rect rgba(200,120,60,0.15)
  Note over ES,EDB: uma transação
  ES->>EDB: tenta baixar saldo dos itens
  EDB-->>ES: saldo insuficiente em algum item
  ES->>EDB: desfaz baixas, grava reserva_pedido negativa
  ES->>EDB: INSERT outbox_event EstoqueIndisponivelEvent
  end
  ES->>K: publica estoque-indisponivel via relay
  K->>PS: estoque-indisponivel
  PS->>PDB: UPDATE pedido para CANCELADO somente se PENDENTE

  Note over ES,K: Redelivery do mesmo pedido-criado
  K->>ES: pedido-criado repetido
  ES->>EDB: reserva_pedido já existe, reutiliza resultado
  ES->>ES: conta estoque.reserva.idempotency.conflict
```

_A reentrega do mesmo evento não baixa saldo de novo: o resultado gravado por `pedidoId` é a fonte da resposta._

## 5. Retry, DLT e mensagem corrompida

Vale para os três consumers. A decisão central é se o erro é transitório (tenta de novo) ou se o registro nunca vai processar (vai direto ao DLT com os bytes originais).

```mermaid
flowchart TD
  M(["Registro chega ao consumer"]) --> D{"ErrorHandlingDeserializer<br/>consegue desserializar?"}
  D -->|"não"| X["DeserializationException<br/>fatal, sem retry"]
  X --> DLT[("tópico -dlt<br/>com bytes originais<br/>BytesPreservingJsonSerializer")]
  DLT --> PM["RetryListener conta<br/>kafka.poison.records"]
  D -->|"sim"| L["@KafkaListener processa"]
  L --> OK{"sucesso?"}
  OK -->|"sim"| COMMIT(["commit do offset"])
  OK -->|"não"| A{"tentativas < 3?"}
  A -->|"sim"| R[("tópico -retry-N<br/>backoff fixo")]
  R --> L
  A -->|"não"| DLT2[("tópico -dlt")]
  DLT2 --> H["@DltHandler<br/>loga e conta kafka.dlt.records<br/>com source_topic e exceção"]
  PM --> H2["monitor dlt-kafka no Datadog"]
  H --> H2
```

_Um registro que não desserializa nunca chega ao `@DltHandler`, por isso a contagem `kafka.poison.records` existe à parte. Não há ferramenta de replay: o DLT é só inspeção e alerta (backlog 4)._

## 6. Como o outbox publica

O mesmo desenho existe nos dois serviços, cada um com sua própria tabela `outbox_event`.

```mermaid
sequenceDiagram
  autonumber
  participant N as Regra de negócio
  participant DB as Postgres do serviço
  participant R as OutboxRelay @Scheduled 1s
  participant CL as OutboxClaimService
  participant K as Kafka

  N->>DB: grava dado de negócio e outbox_event na mesma transação
  loop a cada app.outbox.poll-delay-ms (1000)
    R->>CL: reivindica até app.outbox.batch-size (25)
    CL->>DB: seleciona e marca eventos pendentes
    R->>K: send com header event-id, espera até 10s pelo ack
    alt Kafka confirmou
      R->>CL: markPublished
      CL->>DB: marca evento como publicado
    else falha ou timeout
      R->>CL: releaseAfterFailure com o erro
      CL->>DB: devolve o evento à fila e registra a tentativa
    end
  end
```

_Garantia: pelo menos uma vez. Duplicatas são absorvidas no destino (reserva idempotente por pedido, transição só a partir de PENDENTE)._

## 7. Estados do pedido

```mermaid
stateDiagram-v2
  [*] --> PENDENTE: POST /api/pedidos
  PENDENTE --> CONFIRMADO: estoque-reservado
  PENDENTE --> CANCELADO: estoque-indisponivel
  CONFIRMADO --> CONFIRMADO: evento duplicado ignorado, conta pedido.transicao.conflito
  CANCELADO --> CANCELADO: evento duplicado ignorado, conta pedido.transicao.conflito
```

_Só PENDENTE aceita transição. Se o evento chega antes de o pedido estar visível, o consumer falha e entra no retry (`pedido.evento.pedido_nao_visivel`)._

## Onde evoluir

Ver o backlog em `AGENTS.md` (itens 1 a 6). Lacuna sem item próprio: pedido PENDENTE sem timeout nem compensação quando o estoque nunca responde e o registro fica no DLT.
