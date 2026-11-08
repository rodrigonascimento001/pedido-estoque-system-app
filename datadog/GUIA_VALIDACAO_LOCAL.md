# Validar Datadog localmente

Este guia ativa e valida logs, métricas e traces dos três serviços Java (`api-gateway`, `pedido-service` e `estoque-service`) usando o Agent do Datadog no perfil `observability` do Docker Compose.

## 1. Pré-requisitos

- Docker Desktop ativo e `docker compose` funcionando.
- Uma conta Datadog e uma **API key**. A chave é usada pelo Agent para enviar telemetria.
- O endereço do site Datadog da sua organização (por exemplo, `datadoghq.com` para US1 ou `datadoghq.eu` para EU1).

Não compartilhe nem faça commit da API key. O arquivo `.env` está no `.gitignore` do projeto.

## 2. Configurar o ambiente

Na raiz do projeto, copie o exemplo:

```bash
cp .env.example .env
```

No Windows PowerShell, use:

```powershell
Copy-Item .env.example .env
```

Edite `.env` e preencha/ative estas variáveis:

```dotenv
DD_API_KEY=sua_api_key
DD_SITE=datadoghq.com
DD_ENV=local
SPRING_PROFILES_ACTIVE=datadog
DD_AGENT_HOST=datadog-agent
DD_TRACE_ENABLED=true
```

Use o `DD_SITE` da sua organização. O host `datadog-agent` é o nome do serviço na rede interna do Compose. O profile Spring `datadog` liga a exportação Micrometer via DogStatsD; `DD_TRACE_ENABLED=true` habilita a instrumentação APM do Java Agent.

## 3. Subir ou recriar o ambiente com observabilidade

Na raiz do projeto:

```bash
docker compose --profile observability up -d --build --force-recreate
```

O comando recria também os serviços Java para aplicar as variáveis do `.env`. Aguarde alguns instantes e confira:

```bash
docker compose --profile observability ps
docker compose logs --tail=100 datadog-agent
docker compose logs --tail=100 api-gateway pedido-service estoque-service
```

O Agent precisa estar `Up`. Nos logs de cada Java deve aparecer que o `JAVA_TOOL_OPTIONS` carregou `/opt/datadog/dd-java-agent.jar`. Se a imagem já foi construída anteriormente, `--build` garante que o tracer do Dockerfile está no container.

Confira o diagnóstico do Agent:

```bash
docker compose exec datadog-agent agent status
```

Na saída, procure as seções **API**, **DogStatsD**, **APM** e **Logs Agent**. A API deve estar autenticada; DogStatsD deve escutar em UDP `8125`; APM deve receber traces (porta `8126`); e a coleta de logs deve estar ativa.

## 4. Gerar tráfego de negócio

Use a collection [Insomnia](../insomnia/pedido-estoque-insomnia.json) criada para o projeto:

1. Execute `00 - Saúde e preparação`.
2. Execute `01 - Fluxo feliz`: crie o pedido e consulte-o até `CONFIRMADO`.
3. Execute `02 - Saldo insuficiente`: crie o pedido e consulte-o até `CANCELADO`.

Todas as chamadas de negócio passam pelo gateway com `Authorization: Bearer dev-token`. O fluxo cria requisições HTTP e eventos Kafka, o que deve gerar traces, logs e métricas nos três serviços.

Também é possível gerar uma chamada simples pelo terminal:

```bash
curl -i http://localhost:8080/actuator/health
```

## 5. Validar métricas

Primeiro confirme que a aplicação tem as métricas e o profile esperado:

```bash
curl http://localhost:8081/actuator/metrics
curl http://localhost:8081/actuator/metrics/pedido.criado
curl http://localhost:8082/actuator/metrics/estoque.reserva
```

No PowerShell, a alternativa é:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/metrics
Invoke-RestMethod http://localhost:8081/actuator/metrics/pedido.criado
Invoke-RestMethod http://localhost:8082/actuator/metrics/estoque.reserva
```

Depois de enviar pedidos pela collection, abra Datadog **Metrics > Explorer** ou **Metrics > Summary**. Pesquise as métricas abaixo e filtre por `env:local` e pelo serviço indicado. A ingestão pode levar um pouco para aparecer.

| Métrica ou prefixo | Serviço | Como gerar |
|---|---|---|
| `pedido.criado` | `pedido-service` | Criar qualquer pedido válido |
| `pedido.confirmado` | `pedido-service` | Criar pedido com saldo suficiente |
| `pedido.cancelado` | `pedido-service` | Criar pedido sem saldo suficiente |
| `pedido.saga.duracao` | `pedido-service` | Aguardar o desfecho de um pedido |
| `estoque.reserva` | `estoque-service` | Processar pedidos; inclui resultado da reserva |
| `estoque.compensacao` | `estoque-service` | Necessário falhar um pedido com mais de um item, após reservar o primeiro |
| `http.server.requests` | Cada serviço HTTP | Enviar chamadas à API |
| `spring.cloud.gateway.requests` | `api-gateway` | Enviar chamadas pelo gateway |
| `jvm.*` | Cada serviço Java | Métricas de runtime vêm do Java Agent |

As configurações `application-datadog.yml` aplicam uma allowlist: os prefixos de negócio, HTTP, HikariCP e lag do consumer selecionados são enviados pelo Micrometer. Métricas fora da allowlist não aparecerão. Métricas de JVM são enviadas pelo Java Agent.

## 6. Validar traces (APM)

No Datadog, abra **APM > Traces** e filtre por `env:local`. Procure os serviços `api-gateway`, `pedido-service` e `estoque-service`; use uma janela de tempo que inclua as chamadas feitas no passo 4.

O caminho esperado para um pedido confirmado inclui a entrada HTTP no gateway, o pedido, a publicação/consumo Kafka e a resposta do estoque. Abra um trace e confira os spans de HTTP, JDBC e Kafka. Para a saga, a propagação de contexto Kafka pode ligar os spans ao trace original.

Se nenhuma trace aparecer, confira primeiro `DD_TRACE_ENABLED=true`, `DD_AGENT_HOST=datadog-agent`, o estado da seção APM em `agent status` e o log de inicialização dos containers Java indicando o Java Agent.

## 7. Validar logs e correlação com traces

No Datadog, abra **Logs > Explorer** e filtre, por exemplo:

```text
service:pedido-service env:local
```

Repita com `service:estoque-service` e `service:api-gateway`. Os serviços usam logs JSON quando o profile `datadog` está ativo. Os logs do fluxo devem conter mensagens de pedido criado/confirmado/cancelado e de reserva processada.

Abra um log de aplicação e procure os campos `dd.trace_id` e `dd.span_id`. Quando houver trace associado, use a ação de correlação do log para abrir o trace. A correlação depende da injeção habilitada por `DD_LOGS_INJECTION=true` no Compose.

## 8. Dashboard e monitores do projeto (opcional)

O arquivo `datadog/dashboard-pedidos-estoque.json` usa `layout_type: "ordered"` com layout `fixed`. No Datadog, crie um dashboard novo do tipo **Ordered** (em algumas telas/contas aparece como **Timeboard**) e, nesse dashboard, escolha **Configure > Import dashboard JSON**. Não importe o JSON em um dashboard existente do tipo **Free**: a importação substitui o conteúdo, mas não converte o tipo de layout. A API do Datadog também define `layout_type` como `ordered` ou `free` e informa que o tipo não é alterável num dashboard existente.

Importe os arquivos de `datadog/monitors/` individualmente em **Monitors > New Monitor > Import from JSON**. O dashboard fica mais informativo depois que houver tráfego e métricas.

O script `datadog/importar.sh` também cria o dashboard e [dashboard-pedidos-estoque.json](dashboard-pedidos-estoque.json)os monitores pela API, mas exige `DD_APP_KEY` além de `DD_API_KEY` e cria novos objetos a cada execução. Para evitar duplicatas, prefira a importação manual na primeira validação.

## 9. Diagnóstico rápido

### Agent não conecta

Confira se `.env` contém a chave certa e o site correspondente à organização:

```bash
docker compose --profile observability logs --tail=200 datadog-agent
docker compose --profile observability exec datadog-agent agent status
```

### Métricas não aparecem

- Confirme `SPRING_PROFILES_ACTIVE=datadog` nos containers Java (`docker compose exec pedido-service printenv SPRING_PROFILES_ACTIVE`).
- Confirme `DD_AGENT_HOST=datadog-agent` e a seção DogStatsD em `agent status`.
- Gere tráfego; os counters de negócio só são emitidos quando a ação correspondente ocorre.
- Verifique se o nome procurado está na allowlist do serviço.

### Traces não aparecem

- Confirme `DD_TRACE_ENABLED=true` e `DD_AGENT_HOST=datadog-agent` nos serviços.
- Confirme o Java Agent no log de inicialização e APM ativo em `agent status`.
- Confira os logs do Agent para erros de conexão com o Trace Agent.

### Logs de containers não aparecem

- Confirme `DD_LOGS_ENABLED=true` e `DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=true` no Agent.
- Confirme a seção Logs Agent no `agent status`.
- Este Compose monta caminhos Linux do Docker (`/var/lib/docker/containers`, socket, `/proc` e cgroups). Se o Agent funcionar mas não conseguir descobrir/ler logs no Docker Desktop, verifique o acesso aos mounts de containers no Docker Desktop; os caminhos e o modo de coleta podem exigir ajuste específico do host.
- Use `docker compose logs` para separar problemas de geração de log da coleta pelo Agent.

## 10. Desligar o Agent

Para parar o ambiente incluindo o serviço com perfil:

```bash
docker compose --profile observability down
```

Isso não remove os volumes nomeados dos bancos. Não use `-v` se quiser preservar os dados locais.

## Referências oficiais

- [Datadog Agent para Docker: coleta de logs](https://docs.datadoghq.com/containers/docker/log/)
- [DogStatsD](https://docs.datadoghq.com/extend/dogstatsd/)
- [Configuração Java do Datadog Tracing](https://docs.datadoghq.com/tracing/trace_collection/library_config/java/)
