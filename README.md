# Pedido / Estoque — Skeleton Spring Boot + Kafka

Implementação inicial da arquitetura esboçada: Client → CDN/LB → API Gateway
→ Microsserviços [stateless] → Event Bus (Kafka) → Banco de dados (+ réplicas/cache).

## Módulos

- **common** — eventos compartilhados (`PedidoCriadoEvent`, `EstoqueReservadoEvent`, `EstoqueIndisponivelEvent`)
- **api-gateway** — Spring Cloud Gateway: roteamento, autenticação (stub Bearer), rate limit (Redis)
- **pedido-service** — cria pedidos (status `PENDENTE`), publica `PedidoCriadoEvent`, escuta a resposta do estoque
- **estoque-service** — reserva estoque (lock otimista), publica `EstoqueReservadoEvent`/`EstoqueIndisponivelEvent`

## Saga de consistência eventual

```
pedido-service                    estoque-service
     |  POST /api/pedidos               |
     |  status = PENDENTE                |
     |──── pedido-criado (Kafka) ───────>|
     |                                   | tenta reservar cada item
     |                                   | (lock otimista por produto)
     |<─── estoque-reservado ────────────| sucesso: status = CONFIRMADO
     |<─── estoque-indisponivel ─────────| falha: repõe itens já reservados
     |         status = CANCELADO        | (compensação) e publica falha
```

> Nota: a publicação do evento hoje ocorre logo após o `save()`, fora de uma
> transação com o commit do banco. Para produção, evoluir para o
> **transactional outbox pattern** (tabela outbox + poller/Debezium) evita
> publicar o evento se o commit falhar.

## Subindo o ambiente local completo

A infraestrutura e as aplicações agora são gerenciadas por Compose separados em dois repositórios. Inicie a infraestrutura primeiro, no diretório do repositório `pedido-estoque-system-infra`:

```bash
docker compose up -d
docker compose ps
```

Aguarde Kafka, Redis e os dois Postgres ficarem saudáveis. Em seguida, no diretório deste repositório (`pedido-estoque-system-app`), inicie as aplicações:

```bash
docker compose up -d --build
```

O Compose de infraestrutura cria a rede compartilhada `pedido-estoque-network`. O Compose de aplicação usa essa rede e resolve os serviços de infraestrutura pelos nomes `kafka`, `redis`, `postgres-pedidos` e `postgres-estoque`.

As APIs ficam disponíveis em `localhost:8080` (gateway), `localhost:8081` (pedido) e `localhost:8082` (estoque). Para observar os containers, use `docker compose ps` e `docker compose logs -f` em cada repositório.

Para parar sem remover os dados persistidos, execute `docker compose down` primeiro no repositório da aplicação e depois no repositório de infraestrutura. Não use `-v` se quiser preservar os volumes dos bancos.

O Datadog Agent e opcional e pertence ao Compose de infraestrutura. Configure `DD_API_KEY` e `DD_SITE` no `.env` de `pedido-estoque-system-infra`. No `.env` deste repositório de aplicação, habilite `SPRING_PROFILES_ACTIVE=datadog`, `DD_TRACE_ENABLED=true` e `DD_AGENT_HOST=datadog-agent`. Veja os passos em [Subindo com o Datadog](#subindo-com-o-datadog).

## CI e publicação de imagens

O GitHub Actions executa `mvn -B verify` em pull requests e alterações integradas às branches `develop` e `main`. Após uma alteração integrada em `main` passar pela verificação, as imagens privadas de `pedido-service`, `estoque-service` e `api-gateway` são publicadas no GHCR com uma tag baseada no SHA do commit e a tag móvel `main`. Alterações em `develop` não publicam imagens. A publicação não realiza deploy.
## Build e execução

```bash
mvn clean install

# em terminais separados:
DB_PORT=5433 mvn -pl estoque-service spring-boot:run
mvn -pl pedido-service spring-boot:run
mvn -pl api-gateway spring-boot:run
```

## Testando o fluxo

```bash
# 1. cadastrar estoque de um produto
curl -X POST http://localhost:8082/api/estoque \
  -H "Content-Type: application/json" \
  -d '{"produtoId":"11111111-1111-1111-1111-111111111111","quantidade":10}'

# 2. criar pedido via gateway (rota exige header Authorization: Bearer <qualquer-token>, é um stub)
curl -X POST http://localhost:8080/api/pedidos \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-token" \
  -d '{"itens":[{"produtoId":"11111111-1111-1111-1111-111111111111","quantidade":2,"precoUnitario":19.90}]}'

# 3. consultar status do pedido (assíncrono — pode levar alguns ms até virar CONFIRMADO)
curl http://localhost:8080/api/pedidos/{id} -H "Authorization: Bearer dev-token"
```

## Testes de integração (Testcontainers)

`pedido-service` e `estoque-service` têm testes de integração que sobem
Postgres e Kafka reais em containers Docker — não usam H2 nem Kafka embarcado,
então o Docker precisa estar rodando na máquina que executa os testes.

```bash
mvn -pl pedido-service,estoque-service -am test
```

O que cada suíte cobre:

- **pedido-service**
  - `PedidoIntegrationTest` — `POST /api/pedidos` persiste como `PENDENTE` e
    publica `PedidoCriadoEvent` no tópico `pedido-criado` (lido de volta com
    um `KafkaConsumer` de teste); validação de payload vazio (400) e pedido
    inexistente (404).
  - `EstoqueEventConsumerIntegrationTest` — publica `EstoqueReservadoEvent` /
    `EstoqueIndisponivelEvent` diretamente no tópico e verifica que o pedido
    muda para `CONFIRMADO` / `CANCELADO` (fecha a ponta da saga sem depender
    do estoque-service estar no ar).
- **estoque-service**
  - `EstoqueServiceIntegrationTest` — regra de reserva/compensação contra
    Postgres real (producer Kafka mockado): saldo suficiente reserva
    corretamente, saldo insuficiente lança exceção sem alterar o saldo, e um
    pedido com múltiplos itens onde o 2º falha reverte o 1º já reservado.
  - `PedidoEventConsumerIntegrationTest` — ponta a ponta via Kafka real:
    publica `pedido-criado` e verifica o evento de resposta (`estoque-reservado`
    ou `estoque-indisponivel`) e o saldo final no banco.

## Observabilidade com Datadog (logs, métricas e traces)

| Pilar | Como funciona |
|---|---|
| **Traces (APM)** | `dd-java-agent` carregado via `JAVA_TOOL_OPTIONS` (ver `Dockerfile`). Instrumenta automaticamente HTTP, Spring MVC/WebFlux/Gateway, JDBC e **Kafka** (o contexto do trace é propagado nos headers das mensagens, então um `POST /api/pedidos` aparece como um único trace: gateway → pedido-service → `pedido-criado` → estoque-service → `estoque-reservado` → pedido-service). |
| **Métricas** | Micrometer → registry StatsD com `flavor: datadog` → DogStatsD (UDP 8125) do Agent, filtradas por uma allowlist (ver "Política de métricas"). Passam as métricas de negócio, HTTP, HikariCP e o lag do consumer Kafka. As métricas de JVM vêm do agente (`DD_RUNTIME_METRICS_ENABLED`). |
| **Logs** | Perfil `datadog` troca o console por JSON (`logstash-logback-encoder`). O agente injeta `dd.trace_id`/`dd.span_id` no MDC (`DD_LOGS_INJECTION=true`), então cada log liga ao seu trace. O Agent coleta os logs do stdout dos containers. |

O perfil Spring `datadog` (arquivos `application-datadog.yml` e `logback-spring.xml`) só é ativado
dentro do compose; em dev local e nos testes o comportamento é o de antes (log em texto, sem exportar métricas).

### Métricas de negócio da saga

| Métrica | Tipo | Tags | Onde |
|---|---|---|---|
| `pedido.criado` | counter | — | pedido-service |
| `pedido.confirmado` / `pedido.cancelado` | counter | — | pedido-service |
| `pedido.saga.duracao` | timer | `resultado` (confirmado/cancelado) | pedido-service — tempo entre criar o pedido e receber a resposta do estoque |
| `estoque.reserva` | counter | `resultado` (sucesso/saldo_insuficiente/produto_inexistente) | estoque-service |
| `estoque.compensacao` | counter | — | estoque-service — itens revertidos por falha parcial |

> Todas as métricas recebem automaticamente as tags `service` e `env`.
> Não use IDs (pedidoId, produtoId) como tag: a cardinalidade explode e o custo também.
> O ID fica no log, que já carrega o `trace_id`.

### Política de métricas (cardinalidade e custo)

Cada combinação única de nome + tags vira uma série e conta como métrica customizada no Datadog.
Por isso o perfil `datadog` (`application-datadog.yml` de cada serviço) funciona como **allowlist**:
`management.metrics.enable.all=false` e só os prefixos listados passam.

| Serviço | Liberado |
|---|---|
| pedido-service | `pedido.*`, `http.server.requests`, `hikaricp.*`, lag do consumer Kafka |
| estoque-service | `estoque.*`, `http.server.requests`, `hikaricp.*`, lag do consumer Kafka |
| api-gateway | `spring.cloud.gateway.requests`, `http.server.requests` |

Decisões:

- **Kafka**: as métricas dos clients têm tags por `topic`/`partition`/`node.id` (centenas de séries por
  serviço). Só o lag do consumer é enviado; o restante, via integração Kafka do Datadog.
- **JVM**: uma só fonte, o agente (`jvm.heap_memory`, `jvm.gc.*`...). As `jvm.*` do Micrometer ficam desligadas.
- **Tag `version`**: fora das métricas (cada deploy criaria séries novas). Continua no APM e nos logs (`DD_VERSION`).
- **Métrica nova** com prefixo fora da lista (ex.: `pagamento.*`) **não aparece** no Datadog até ser
  liberada no `enable:` do serviço. É de propósito: revisar as tags da métrica é a hora de pensar na cardinalidade.

Como conferir (com o serviço rodando em perfil `datadog`): `curl localhost:8081/actuator/metrics`
deve listar apenas nomes dos prefixos liberados. Para acompanhar o volume real, use *Metrics Summary*
e a página de *Usage* (métricas customizadas) no Datadog.

> Estas regras só valem no perfil `datadog`; em dev local e nos testes todas as métricas continuam ativas.

### Spans customizados

Critério: só se cria span onde a auto-instrumentação (HTTP, JDBC, Kafka) não enxerga algo que
vale medir ou diagnosticar separadamente. Para dado de contexto, prefira **tag** em span existente.

| Span / tag | Onde | Por quê |
|---|---|---|
| `estoque.reservar_item` (tags `produto.id`, `reserva.quantidade`, `reserva.resultado`) | `ReservaEstoqueService.reservar` | uma reserva por item; mostra qual produto gera contenção |
| `estoque.repor_item` | `ReservaEstoqueService.repor` | reposição individual na compensação |
| `estoque.compensar` (tag `compensacao.itens`) | `EstoqueService.compensar` | custo de reverter uma saga com falha parcial |
| tag `pedido.id` no span de entrada | `PedidoService.criar`, consumers Kafka dos dois serviços | achar no APM o trace de um pedido específico |

Convenções: IDs só em tags de span (nunca no nome da operação nem em tag de métrica); falha de
negócio (sem saldo, produto inexistente) é *resultado*, não exceção, para não contar como erro no
APM; `SpanTags` (módulo `common`) nunca lança exceção e é no-op sem o agente (dev local e testes).
O `@Trace` cobre o corpo do método; o commit da transação acontece depois, no proxy do Spring.

### Dashboard e monitores (JSON para importar)

Pasta `datadog/`:

| Arquivo | O que é |
|---|---|
| `dashboard-pedidos-estoque.json` | 25 widgets: saga (criados/confirmados/cancelados, taxa de cancelamento, duração), estoque (reservas por resultado, compensações, lock), API (volume, p95, 5xx, gateway), infra (heap, threads, HikariCP, lag Kafka) e logs de warn/error. Variáveis `env` e `service` no topo. |
| `monitors/taxa-cancelamento-pedidos.json` | cancelados / (cancelados + confirmados) > 20% em 15 min (warning 10%) |
| `monitors/duracao-saga-p95.json` | p95 da saga > 5 s em 10 min (warning 2 s) |
| `monitors/lag-consumer-kafka.json` | lag do consumer > 500 por serviço (warning 100) |
| `monitors/taxa-5xx-servicos.json` | 5xx > 5% por serviço em 5 min (warning 2%) |
| `monitors/heap-jvm.json` | heap > 90% do máximo por serviço (warning 80%) |
| `monitors/conflito-lock-estoque.json` | mais de 20 conflitos de lock otimista em 10 min (warning 5) |

**Importar pela UI**

- Dashboard: *Dashboards > New Dashboard*, abra o menu de configuração (engrenagem) e escolha *Import dashboard JSON*, colando o arquivo.
- Monitores: *Monitors > New Monitor > Import from JSON* (um arquivo por vez).

**Importar pela API** (cria tudo de uma vez; rodar de novo duplica os objetos):

```bash
export DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.com
./datadog/importar.sh
```

Antes de usar em produção:

- Os monitores usam escopo `{*}` (todos os ambientes). Troque por `{env:prod}` na query para não alertar sobre dev.
- Nenhum monitor tem destinatário. Acrescente `@time-pedidos`, `@slack-...` ou e-mail no fim de `message`.
- Os limiares são pontos de partida; ajuste depois de ver o comportamento real (principalmente o p95 da saga).
- Monitores de taxa oscilam com pouco volume (poucos pedidos por janela); aumente a janela ou o limiar se for o caso.
- O dashboard usa só métricas liberadas na allowlist e as de JVM do agente. Um widget vazio quase sempre
  é nome de métrica diferente do esperado: confira em *Metrics Summary*.

### Subindo com o Datadog
Na pasta do repositório de infraestrutura, copie `.env.example` para `.env`, preencha `DD_API_KEY` com a chave da sua organização e ajuste `DD_SITE` se sua conta não usar `datadoghq.com`. Depois inicie o Agent:

```powershell
# No repositório pedido-estoque-system-infra
Copy-Item .env.example .env # faça isso apenas se o arquivo .env ainda não existir
notepad .env              # informe DD_API_KEY e, se necessário, DD_SITE
docker compose --profile observability up -d datadog-agent
```

No `.env` deste repositório de aplicação, habilite `SPRING_PROFILES_ACTIVE=datadog` e `DD_TRACE_ENABLED=true` (mantenha `DD_AGENT_HOST=datadog-agent`) e recrie os serviços:

```powershell
# No repositório pedido-estoque-system-app
Copy-Item .env.example .env # faça isso apenas se o arquivo .env ainda não existir
notepad .env              # habilite SPRING_PROFILES_ACTIVE=datadog e DD_TRACE_ENABLED=true
docker compose up -d --build --force-recreate
```

Depois de gerar tráfego (curls da seção "Testando o fluxo", mas com estoque cadastrado via
`localhost:8082`), procure no Datadog:

- **APM → Traces**: serviços `api-gateway`, `pedido-service`, `estoque-service`, `env:local`
- **Metrics Explorer**: `pedido.saga.duracao.avg`, `estoque.reserva`, `jvm.heap_memory`
- **Logs**: `service:pedido-service` — abra um log e use *View trace*

Sem iniciar o Agent com o perfil Compose `observability`, o ambiente local funciona sem enviar telemetria ao Datadog.

Pontos de atenção:

- O Agent monta `/var/run/docker.sock` e `/var/lib/docker/containers` para coletar logs; em Docker
  Desktop (Mac/Windows) o path de containers pode precisar de ajuste.
- `DD_TRACE_SAMPLE_RATE=1` (100%) é escolha de dev; em produção, reduza a amostragem.
- A versão do `dd-java-agent` (`ARG DD_TRACER_VERSION` no Dockerfile) deve acompanhar
  `dd-trace-api.version` do pom pai; atualize os dois juntos.
- Em Kubernetes/ECS o Agent roda como DaemonSet/sidecar e o `DD_AGENT_HOST` aponta para ele; o
  código da aplicação não muda.

## Próximos passos sugeridos

- [ ] Autenticação real no gateway (validação JWT — emissor, expiração, escopos)
- [ ] Transactional outbox no pedido-service e no estoque-service
- [ ] Cache Redis para leitura de pedidos/estoque (reduzir carga no banco primário)
- [ ] Load balancer / múltiplas réplicas dos serviços (Docker Compose `deploy.replicas` ou k8s)
- [ ] Réplica de leitura do Postgres para consultas (`GET /api/pedidos/{id}`)
- [x] Observabilidade com Datadog: logs JSON, métricas (DogStatsD) e traces (dd-java-agent)
- [x] Dashboards e monitores no Datadog em JSON (`datadog/`)
- [ ] Monitor de "saga travada" (pedidos PENDENTE por mais de N minutos); exige uma métrica de gauge com a
  contagem de pendentes, que hoje não existe
- [x] Spans customizados com `dd-trace-api` (`@Trace`) e tag `pedido.id` nos spans de entrada
- [ ] Idempotência do consumer `pedido-criado` (tabela de eventos processados): se, esgotados os retries
  de lock otimista, a exceção chegar ao Kafka, o evento é reentregue e itens já reservados seriam reservados de novo
- [ ] Pipeline de CI rodando os testes de integração (GitHub Actions com `docker` disponível no runner)
- [x] Testes de integração com Testcontainers (Kafka + Postgres)
