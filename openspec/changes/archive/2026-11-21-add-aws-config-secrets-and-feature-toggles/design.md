# Design: Segredos de Configuração AWS e Feature Toggles

## Contexto

- Os serviços atualmente leem configurações de banco, Kafka e Redis de variáveis de ambiente, com valores padrão inseguros em `application.yml` (por exemplo `${DB_USER:postgres}` / `${DB_PASSWORD:postgres}`).
- A observabilidade é controlada pelo perfil Spring `datadog` (`application-datadog.yml`) e por variáveis `DD_*` definidas no ambiente de deploy. O agente Datadog e o `dd-java-agent` são configurados fora da aplicação e lidos antes de o contexto Spring existir.
- O repositório de infraestrutura (`pedido-estoque-system-infra`) é responsável por Kafka, ZooKeeper, Redis, PostgreSQL e pelo agente Datadog opcional. O LocalStack ainda não existe nele.
- Stack: Java 21, Spring Boot 3.2.5, Spring Cloud 2023.0.1, Maven, Testcontainers 1.19.7, GitHub Actions executando `mvn -B verify`.
- Restrições:
  - Os segredos precisam estar disponíveis enquanto o `Environment` é preparado, antes da autoconfiguração do `DataSource`.
  - A CI e os testes existentes precisam continuar funcionando sem qualquer acesso à AWS.
  - O perfil padrão precisa preservar exatamente o comportamento atual.

## Objetivos / Não-Objetivos

### Objetivos

- Obter credenciais de banco do AWS Secrets Manager em deploys na AWS.
- Expor feature toggles globais com valores seguros a partir do AWS Parameter Store, começando pelo Datadog.
- Manter o desenvolvimento local e os testes totalmente offline da AWS real usando LocalStack.
- Manter a mudança reversível ativando ou desativando um único perfil Spring.

### Não-Objetivos

- Kafka SASL/SCRAM ou Redis AUTH. Não há credenciais a migrar hoje; isso fica adiado para uma mudança futura.
- Alterar o ciclo de vida do agente Datadog, o tracing ou a injeção de logs pelo Parameter Store. Esses são aspectos de deploy.
- Atualização do toggle em tempo de execução sem reinício. Os toggles são lidos na inicialização.
- Rotação automática de segredos. A rotação exigirá reinício.
- Provisionar infraestrutura AWS real (papéis IAM, criação de segredos) a partir deste repositório. Apenas os dados de seed do LocalStack são incluídos.

## Decisões

### D1: Usar Spring Cloud AWS 3.2.x em vez do AWS SDK puro

Adicionar `io.awspring.cloud:spring-cloud-aws-dependencies` como BOM e os starters de Secrets Manager e Parameter Store no módulo `common`. O Spring Cloud AWS 3.2.x é compatível com Spring Boot 3.2.x e Spring Cloud 2023.0.x, e oferece imports de config data que são executados durante a preparação do environment.

Alternativas rejeitadas:
- AWS SDK v2 puro com um `EnvironmentPostProcessor` customizado: mais código, riscos de ordenação e lógica duplicada.
- Carregar segredos em `@PostConstruct` ou `ApplicationRunner`: tarde demais para a criação do `DataSource`.

### D2: Imports de configuração explícitos por serviço, condicionados ao perfil

Cada serviço recebe um `application-aws.yml` com suas próprias entradas de `spring.config.import`. O arquivo só é carregado quando o perfil `aws` está ativo. Nenhum import é adicionado ao `application.yml` padrão.

```yaml
# pedido-service/src/main/resources/application-aws.yml
spring:
  config:
    import:
      - aws-secretsmanager:pedido-estoque/${APP_ENV:local}/pedido-service/db
      - optional:aws-parameterstore:/pedido-estoque/${APP_ENV:local}/common/
```

```yaml
# estoque-service/src/main/resources/application-aws.yml
spring:
  config:
    import:
      - aws-secretsmanager:pedido-estoque/${APP_ENV:local}/estoque-service/db
      - optional:aws-parameterstore:/pedido-estoque/${APP_ENV:local}/common/
```

O `api-gateway` importa apenas o caminho comum de parâmetros (sem segredo de banco).

Racional: os imports são explícitos, descobríveis e limitados ao perfil que precisa deles. Não é necessário bootstrap phase nem `EnvironmentPostProcessor`, conforme a decisão do usuário.

Alternativas rejeitadas:
- Imports `optional:` sempre ativos no `application.yml` base: todo teste e execução local tentaria chamadas à AWS.
- Arquivo de configuração compartilhado no `common`: config data de classpath vindo de uma dependência é mais difícil de raciocinar e ainda exigiria segredos por serviço.

### D3: A política de falha difere entre segredos e toggles

- Segredos não são opcionais sob o perfil `aws`. Um segredo ausente ou ilegível faz o startup falhar. Isso evita o fallback silencioso para `postgres/postgres` em um deploy real.
- Toggles são importados com `optional:`. Um parâmetro ausente usa um valor seguro (`false`).

Racional: segredos são obrigatórios para operar; toggles não são críticos e não devem derrubar um serviço.

Alternativa rejeitada: segredos `optional:` com fallback para valores inseguros, o que esconde erros de configuração.

### D4: Nomenclatura e formato do segredo

Nomes dos segredos:

- `pedido-estoque/{env}/pedido-service/db`
- `pedido-estoque/{env}/estoque-service/db`

O `SecretString` é um JSON com chaves que correspondem aos placeholders já usados pelos serviços:

```json
{
  "AWS_DB_URL": "jdbc:postgresql://postgres:5432/pedidos_db",
  "AWS_DB_USER": "pedido",
  "AWS_DB_PASSWORD": "change-me"
}
```

O `estoque-service` usa `estoque_db` no `AWS_DB_URL`. As chaves recebem o prefixo `AWS_` de propósito: variáveis de ambiente do Compose como `DB_HOST`, `DB_USER`, `DB_PASSWORD` e `DB_NAME` têm precedência sobre o config data import e sobrescreveriam os valores do segredo se os nomes colidissem.

Para evitar o fallback silencioso para `postgres/postgres` quando o segredo existe mas está incompleto, o `application-aws.yml` redeclara as propriedades do datasource sem valores padrão:

```yaml
# application-aws.yml
spring:
  datasource:
    url: ${AWS_DB_URL}
    username: ${AWS_DB_USER}
    password: ${AWS_DB_PASSWORD}
```

Com isso, uma chave ausente deixa um placeholder não resolvido e faz o startup falhar em vez de usar os padrões da base. As variáveis `DB_*` do Compose continuam existindo para o fluxo local sem AWS, mas são ignoradas quando o perfil `aws` sobrescreve o datasource.

Alternativas rejeitadas:
- Reutilizar os nomes `DB_*`: colidiriam com as variáveis de ambiente do Compose, que têm precedência sobre o config data import.
- Chaves nomeadas `spring.datasource.*`: competiriam com os valores do `application.yml`, e a precedência entre propriedades importadas do segredo e o documento que faz o import precisaria de validação extra.
- Segredos separados para host/porta/nome: host e porta não são segredos; um único segredo de conexão mantém a rotação atômica.

### D5: Nomenclatura do parâmetro e mapeamento de propriedades

Parâmetro:

- `/pedido-estoque/{env}/common/features.datadog.enabled` com valor `true` ou `false`.

Caminho de import: `optional:aws-parameterstore:/pedido-estoque/${APP_ENV:local}/common/`.

O sufixo após o caminho importado se torna a chave da propriedade Spring, e os pontos no nome do parâmetro mapeiam para propriedades aninhadas. Isso produz `features.datadog.enabled` sem depender da opção `?prefix=`, cuja disponibilidade varia entre versões do Spring Cloud AWS.

A seleção de ambiente usa `APP_ENV` (padrão `local`) em vez de `spring.profiles.active`, para manter ambiente e perfis de funcionalidade ortogonais.

Alternativas rejeitadas:
- Um parâmetro por serviço: o usuário escolheu um único toggle global.
- Depender de `aws.paramstore.name` / separadores de perfil: são configurações de estilo legado e menos explícitas que um caminho de import completo.

### D6: Semântica do toggle Datadog (decisão C2)

O toggle controla apenas a exportação de métricas StatsD do Micrometer no lado da aplicação. Tracing, injeção de logs e o agente continuam controlados pelas variáveis de ambiente `DD_*` do deploy e não são alterados pelo Parameter Store.

Fiação:

```yaml
# application.yml (base, padrão inalterado)
management:
  statsd:
    metrics:
      export:
        enabled: false
```

```yaml
# application-datadog.yml
management:
  statsd:
    metrics:
      export:
        enabled: ${features.datadog.enabled:true}
```

```yaml
# application-aws.yml
spring:
  config:
    import:
      - optional:aws-parameterstore:/pedido-estoque/${APP_ENV:local}/common/
```

Comportamento resultante:

| Perfis ativos | Exportação StatsD |
| --- | --- |
| nenhum | `false` |
| `datadog` | `true` (fallback padrão) |
| `aws,datadog` | valor de `features.datadog.enabled` (padrão `false` se o parâmetro estiver ausente) |
| `aws` | `false` (StatsD não é configurado sem o perfil `datadog`) |

Racional: deploys existentes com Datadog fora da AWS continuam funcionando com o perfil `datadog`, enquanto deploys na AWS ganham controle via SSM sem mudança de código.

Alternativas rejeitadas:
- Toggle completo incluindo reinício do agente: rejeitado pelo usuário; o reinício é aceitável, mas o ciclo de vida do agente pertence ao deploy.
- Parâmetro mapeado diretamente para `management.statsd.metrics.export.enabled`: funciona, mas perde a chave estável de feature flag solicitada pelo usuário.

### D7: O LocalStack fica no repositório de infraestrutura

Adicionar um serviço `localstack` ao `docker-compose.yml` de infraestrutura por trás do profile de Compose `localstack`:

- Imagem: `localstack/localstack` com versão fixada.
- `SERVICES=secretsmanager,ssm`, porta `4566`, healthcheck.
- Apenas credenciais fictícias (`test` / `test`), região fixa (`us-east-1`).
- Scripts de seed montados em `/etc/localstack/init/ready.d/` e executados de forma idempotente.

O script de seed cria os dois segredos de banco e o parâmetro do toggle. Os serviços no Compose da aplicação recebem:

- `APP_ENV=local`
- `AWS_REGION=us-east-1`
- `AWS_ACCESS_KEY_ID=test` / `AWS_SECRET_ACCESS_KEY=test`
- `AWS_ENDPOINT_URL=http://localstack:4566`
- `SPRING_PROFILES_ACTIVE=aws,datadog` ao testar o caminho do toggle

Alternativas rejeitadas:
- LocalStack no Compose da aplicação: viola o contrato de stacks separados já documentado.
- Emulação apenas para testes: desenvolvedores locais precisam do mesmo caminho.

### D8: Os testes usam Testcontainers LocalStack

Adicionar testes de integração que iniciam um `LocalStackContainer` com Secrets Manager e SSM habilitados, semeiam recursos com clientes do AWS SDK e apontam o contexto Spring para o endpoint do container via propriedades de sistema `spring.cloud.aws.*` definidas antes do config data import. Não é usado um perfil `aws-test` dedicado, pois `@DynamicPropertySource` é aplicado tarde demais para o config data import. Os testes verificam:

- As propriedades do datasource são resolvidas a partir do segredo semeado.
- `features.datadog.enabled` é resolvido a partir do parâmetro semeado e controla `management.statsd.metrics.export.enabled`.
- Um parâmetro opcional ausente usa o valor `false`.
- Os testes existentes não-AWS permanecem inalterados.

Alternativa rejeitada: mockar o config data. Isso não provaria o mapeamento real de segredo/parâmetro.

### D9: Autenticação de Kafka e Redis fica fora de escopo

Nenhum segredo `/common/kafka` ou `/common/redis` é criado nesta mudança. O Kafka continua PLAINTEXT e o Redis sem autenticação. O esquema de nomes deixa espaço para esses segredos em uma mudança futura sem retrabalho.

Racional: não há credencial em uso atualmente; adicionar segredos não utilizados e a fiação expandiria a mudança sem validar nada real.

### D10: Rollout em quatro fases

1. Infraestrutura: serviço LocalStack, scripts de seed, `.env.example` de infraestrutura e documentação.
2. Configuração da aplicação: dependências/BOM no `common`, `application-aws.yml` por serviço, formato do segredo e verificação do fallback local.
3. Observabilidade: fiação do toggle no Parameter Store e documentação do Datadog.
4. Testes e operação: cobertura com Testcontainers, verificação da CI e documentação de IAM/runbook.

Rollback: remover `aws` de `SPRING_PROFILES_ACTIVE`; os serviços voltam ao comportamento por variáveis/`yml` sem reverter código.

## Riscos / Trade-offs

- A precedência do config data import e a resolução de placeholders podem se comportar de forma inesperada em configurações compostas. Mitigação: resolver no spike da fase 2 e cobrir com um teste Testcontainers.
- O mapeamento de chave do Parameter Store para propriedade é sensível à versão. Mitigação: usar um segmento final com pontos e verificar a propriedade resolvida em um teste de integração.
- A descoberta do endpoint do LocalStack depende de a versão do AWS SDK respeitar `AWS_ENDPOINT_URL`. Mitigação: usar `aws.secretsmanager.endpoint` e `aws.paramstore.endpoint` como fallback, se necessário.
- Os segredos são lidos uma única vez na inicialização; a rotação exige reinício. Aceito por enquanto e documentado.
- Segredos com falha rápida podem quebrar um deploy com nome de segredo errado ou IAM insuficiente. Mitigação: smoke check antes do deploy e um runbook com as ações IAM necessárias (`secretsmanager:GetSecretValue`, `ssm:GetParametersByPath`).
- O `application-aws.yml` é duplicado entre os serviços. Aceito pela explicitude; uma refatoração futura pode extraí-lo se a duplicação crescer.
- Limitação da C2: o toggle não controla o agente, o tracing ou a injeção de logs. Documentado explicitamente para que operadores não esperem um kill switch completo do Datadog.
- O build padrão precisa permanecer sem AWS. Mitigação: apenas os testes com LocalStack configuram `spring.cloud.aws.*` no contexto de teste, e o build Maven padrão nunca ativa o perfil `aws`.

## Plano de Migração

1. Adicionar LocalStack e os dados de seed ao repositório de infraestrutura; verificar se os segredos e parâmetros existem.
2. Adicionar o BOM/starters do Spring Cloud AWS ao `common`; confirmar que o build padrão e os testes continuam passando sem AWS.
3. Criar `application-aws.yml` para `pedido-service`, `estoque-service` e `api-gateway`; validar a resolução dos segredos contra o LocalStack.
4. Fiar `features.datadog.enabled` no `application-datadog.yml` e verificar a matriz dos quatro perfis.
5. Adicionar os testes Testcontainers e atualizar a CI se necessário; manter `mvn -B verify` verde.
6. Atualizar `.env.example` e runbooks com `APP_ENV`, endpoint/credenciais AWS e requisitos de IAM.
7. Fazer o deploy com o perfil `aws` primeiro em um ambiente não produtivo e depois promover.

## Questões em Aberto

- Confirmar a versão patch exata do Spring Cloud AWS (última 3.2.x compatível com Boot 3.2.5) no momento da implementação.
- Confirmar se a versão resolvida do AWS SDK respeita `AWS_ENDPOINT_URL`; caso contrário, padronizar as propriedades de endpoint por serviço.
- O `api-gateway` deve importar o caminho comum de toggles? Recomendação: sim, para comportamento global consistente.
- Manter o padrão `true` do perfil `datadog` por compatibilidade retroativa ou exigir um toggle explícito em todo deploy? Recomendação: manter `true`.
- Validar no spike da fase 2 se o formato de três chaves (`AWS_DB_URL`, `AWS_DB_USER`, `AWS_DB_PASSWORD`) cobre todos os ambientes ou se será necessário um formato adicional para conexões com parâmetros extras.
