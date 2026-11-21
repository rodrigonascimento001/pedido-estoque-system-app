## ADDED Requirements

### Requirement: Emular Secrets Manager e Parameter Store no repositório de infraestrutura
O repositório de infraestrutura DEVE (MUST) oferecer um serviço LocalStack com os serviços `secretsmanager` e `ssm` habilitados, disponível apenas por um profile de Compose dedicado. O serviço DEVE expor a porta `4566` e possuir healthcheck. Sem o profile dedicado, o LocalStack NÃO DEVE ser iniciado.

#### Scenario: LocalStack inicia com o profile dedicado
- **QUANDO** o profile `localstack` do Compose de infraestrutura é ativado
- **ENTÃO** o serviço LocalStack inicia com `secretsmanager` e `ssm` habilitados
- **E** o healthcheck indica que o serviço está pronto

#### Scenario: Profile não ativado mantém a infraestrutura atual
- **QUANDO** o Compose de infraestrutura é iniciado sem o profile `localstack`
- **ENTÃO** o LocalStack não é iniciado
- **E** os demais serviços de infraestrutura continuam funcionando como antes

### Requirement: Semear recursos locais de forma idempotente
O repositório de infraestrutura DEVE (MUST) conter scripts de inicialização que criem os segredos de banco e o parâmetro global de toggle no LocalStack. Os scripts DEVEM ser idempotentes, DEVEM usar somente credenciais fictícias e NÃO DEVEM conter credenciais reais.

#### Scenario: Primeira subida cria os recursos
- **QUANDO** o LocalStack inicia pela primeira vez
- **ENTÃO** os segredos `pedido-estoque/local/pedido-service/db` e `pedido-estoque/local/estoque-service/db` são criados
- **E** o parâmetro `/pedido-estoque/local/common/features.datadog.enabled` é criado

#### Scenario: Subidas repetidas não geram erro
- **QUANDO** o LocalStack é reiniciado ou os scripts são executados novamente
- **ENTÃO** os recursos existentes são sobrescritos sem erro de duplicidade
- **E** os valores permanecem consistentes

#### Scenario: Nenhuma credencial real é armazenada
- **QUANDO** os scripts e os arquivos de exemplo são inspecionados
- **ENTÃO** apenas valores fictícios de desenvolvimento são encontrados
- **E** nenhuma credencial de produção ou de nuvem real está presente

### Requirement: Apontar os serviços locais para o LocalStack
O Compose da aplicação DEVE (MUST) fornecer, quando o fluxo local com LocalStack é utilizado, o ambiente (`APP_ENV`), a região, credenciais fictícias, o endpoint local e o perfil `aws` aos serviços. O fluxo local sem LocalStack NÃO DEVE ser alterado.

#### Scenario: Fluxo local com LocalStack resolve segredos e parâmetros
- **QUANDO** os serviços são iniciados no Compose da aplicação com o ambiente e o endpoint do LocalStack
- **ENTÃO** os serviços resolvem os segredos e o parâmetro de toggle a partir do LocalStack
- **E** não realizam chamadas à AWS real

#### Scenario: Fluxo local sem LocalStack continua funcionando
- **QUANDO** os serviços são iniciados sem o perfil `aws`
- **ENTÃO** o fluxo local atual permanece inalterado
- **E** nenhuma dependência do LocalStack é exigida

### Requirement: Cobrir a integração com testes usando Testcontainers LocalStack
A suíte de testes DEVE (MUST) incluir testes de integração que iniciem um LocalStack com `secretsmanager` e `ssm`, semeiem os recursos e apontem o contexto Spring para o container. Os testes NÃO DEVEM depender de AWS real nem de credenciais válidas.

#### Scenario: Teste resolve o segredo de banco
- **QUANDO** o teste de integração inicia o LocalStack e o contexto Spring com o perfil de teste AWS
- **ENTÃO** as propriedades do `DataSource` são resolvidas a partir do segredo semeado
- **E** o contexto inicia com sucesso

#### Scenario: Teste resolve o toggle e controla as métricas
- **QUANDO** o teste semeia `features.datadog.enabled` com `true` ou `false`
- **ENTÃO** a propriedade é resolvida corretamente
- **E** `management.statsd.metrics.export.enabled` reflete o valor do toggle

#### Scenario: Build padrão não requer LocalStack nem AWS
- **QUANDO** `mvn -B verify` é executado sem credenciais AWS
- **ENTÃO** os testes que não usam o perfil de teste AWS continuam passando
- **E** apenas os testes de integração com LocalStack exigem Docker
