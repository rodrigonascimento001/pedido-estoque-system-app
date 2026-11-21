## 1. Infraestrutura local com LocalStack

- [x] 1.1 Adicionar o serviço `localstack` ao `docker-compose.yml` do repositório `pedido-estoque-system-infra`, atrás do profile `localstack`, com imagem fixada, `SERVICES=secretsmanager,ssm`, porta `4566`, healthcheck, região `us-east-1` e credenciais fictícias.
- [x] 1.2 Criar scripts em `localstack/init/ready.d/` que criem os segredos `pedido-estoque/local/pedido-service/db` e `pedido-estoque/local/estoque-service/db` (JSON com `AWS_DB_URL`, `AWS_DB_USER`, `AWS_DB_PASSWORD`) e o parâmetro `/pedido-estoque/local/common/features.datadog.enabled`, de forma idempotente.
- [x] 1.3 Atualizar `.env.example` e a documentação do repositório de infraestrutura com o uso do profile `localstack`, `APP_ENV`, `AWS_REGION`, credenciais fictícias e endpoint local.
- [x] 1.4 Verificar que o LocalStack fica saudável e com os recursos criados via `awslocal`, e que sem o profile `localstack` ele não é iniciado.

## 2. Spring Cloud AWS no módulo common e nos serviços

- [x] 2.1 Adicionar a propriedade de versão e o BOM `io.awspring.cloud:spring-cloud-aws-dependencies` ao `dependencyManagement` do `pom.xml` raiz e os starters de Secrets Manager e Parameter Store ao `common/pom.xml`; executar `mvn -B verify`.
- [x] 2.2 Criar `pedido-service/src/main/resources/application-aws.yml` com import não-opcional do segredo do serviço, import opcional do caminho comum de parâmetros e override do datasource sem valores padrão.
- [x] 2.3 Criar `estoque-service/src/main/resources/application-aws.yml` com o mesmo padrão, usando o segredo e o `DB_NAME` do serviço de estoque.
- [x] 2.4 Criar `api-gateway/src/main/resources/application-aws.yml` importando apenas o caminho comum de parâmetros.
- [x] 2.5 Atualizar o `docker-compose.yml` e o `.env.example` da aplicação com `APP_ENV`, `AWS_REGION`, credenciais fictícias, `AWS_ENDPOINT_URL` e a opção de incluir `aws` em `SPRING_PROFILES_ACTIVE` no fluxo local com LocalStack.
- [x] 2.6 Validar o fluxo local com LocalStack: os serviços resolvem segredo e parâmetro, o perfil padrão permanece inalterado, segredo ausente falha rápido e segredo incompleto não usa `postgres` como fallback.

## 3. Toggle de observabilidade do Datadog

- [x] 3.1 Alterar os três `application-datadog.yml` para `management.statsd.metrics.export.enabled: ${features.datadog.enabled:true}`, mantendo `false` no `application.yml` base.
- [x] 3.2 Documentar a matriz de perfis (`nenhum`, `datadog`, `aws,datadog`, `aws`) e deixar explícito que tracing, injeção de logs e agente continuam sob as variáveis `DD_*` do deploy.
- [x] 3.3 Validar os quatro cenários de perfil com o LocalStack e/ou testes de integração.

## 4. Testes, documentação e validação final

- [x] 4.1 Adicionar a dependência de teste do Testcontainers LocalStack onde necessário e configurar `spring.cloud.aws.*` via propriedades de sistema antes do config data import, apontando para o container (sem perfil `aws-test` dedicado).
- [x] 4.2 Adicionar teste de integração que valide a resolução do segredo de banco e a falha rápida para segredo incompleto.
- [x] 4.3 Adicionar teste de integração que valide o mapeamento de `features.datadog.enabled`, o efeito em `management.statsd.metrics.export.enabled` e o padrão `false` quando o parâmetro estiver ausente.
- [x] 4.4 Garantir que os testes padrão não ativam o perfil `aws` e executar `mvn -B verify` sem credenciais AWS.
- [x] 4.5 Documentar permissões IAM necessárias (`secretsmanager:GetSecretValue`, `ssm:GetParametersByPath`), nomes de segredos/parâmetros, rotação com reinício, runbook operacional e rollback removendo `aws` dos perfis ativos.
- [x] 4.6 Executar `openspec validate "add-aws-config-secrets-and-feature-toggles" --type change` e confirmar que o build padrão permanece verde sem chamadas à AWS (o repositório não possui pipeline de CI configurado).
