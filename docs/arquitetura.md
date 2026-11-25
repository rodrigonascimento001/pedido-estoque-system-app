# Arquitetura do sistema Pedido-Estoque

Estado após a change `api-security-keycloak`. Os diagramas são Mermaid e o GitHub os renderiza nesta página. Atualize-os na mesma change que alterar fluxo, tópicos ou componentes.

## 1. Componentes e dados

Cada serviço tem seu próprio Postgres. A única ligação entre pedido e estoque é o Kafka, e todo evento sai por uma tabela outbox gravada na mesma transação do negócio.

```mermaid
flowchart LR
  C([Cliente / navegador]) -->|"login Authorization Code + PKCE :8180"| KC
  KC -->|"access token assinado"| C
  C -->|"Bearer access token :8080"| GW
  subgraph GWB["api-gateway :8080 / management :9080"]
    GW["Spring Security Resource Server<br/>valida JWT Keycloak: assinatura, iss, aud, exp"] --> RL["RequestRateLimiter<br/>20 req/s, burst 40, por IP"]
  end
  RL <-->|"contadores"| R[("Redis")]
  RL -->|"/api/pedidos/**"| PS
  RL -->|"/api/estoque/**"| ES
  subgraph INFRA["Compose de infraestrutura / rede compartilhada"]
    KC["Keycloak :8080 interno / :8180 host<br/>realm pedido-estoque"]
  end
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

_O navegador autentica o usuário no Keycloak (Authorization Code + PKCE) e recebe um access token. O cliente envia o token ao gateway; o gateway valida assinatura usando o JWKS do Keycloak, `iss`, `aud` e validade temporal antes de aplicar rate limit e rotear a chamada. Não há exigência de roles/scopes nesta etapa. Linhas cheias são o caminho normal; linhas tracejadas indicam consulta de chaves ou desvio para retry/DLT. Postgres, Kafka, Redis e Keycloak ficam no Compose de infraestrutura, separado do Compose da aplicação. Apenas gateway (`8080`) e Keycloak (`8180`) são publicados no host; serviços e management permanecem internos._

## 2. Criar pedido com estoque disponível

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

## 3. Estoque insuficiente

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

## 4. Retry, DLT e mensagem corrompida

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

## 5. Como o outbox publica

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

## 6. Estados do pedido

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
