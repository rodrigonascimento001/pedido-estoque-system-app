## ADDED Requirements

### Requirement: Ler feature toggles globais do AWS Parameter Store
Sob o perfil `aws`, cada serviço DEVE (MUST) importar o caminho comum de parâmetros `/pedido-estoque/{env}/common/` usando importação opcional. O parâmetro `/pedido-estoque/{env}/common/features.datadog.enabled` DEVE ser exposto como a propriedade `features.datadog.enabled`. Quando o parâmetro não existir, a propriedade DEVE assumir o valor seguro `false`.

#### Scenario: Parâmetro presente é exposto como propriedade
- **QUANDO** o perfil `aws` está ativo e o parâmetro `features.datadog.enabled` existe com valor `true`
- **ENTÃO** a propriedade `features.datadog.enabled` é resolvida como `true` no ambiente Spring

#### Scenario: Parâmetro ausente usa o valor seguro
- **QUANDO** o perfil `aws` está ativo e o parâmetro `features.datadog.enabled` não existe
- **ENTÃO** o serviço inicia normalmente
- **E** a propriedade `features.datadog.enabled` assume `false`

#### Scenario: Serviço inicia sem o perfil aws
- **QUANDO** o perfil `aws` não está ativo
- **ENTÃO** nenhuma importação do Parameter Store é realizada
- **E** a propriedade `features.datadog.enabled` permanece com o valor definido localmente ou o padrão `false`

### Requirement: Controlar a exportação de métricas do Datadog pelo toggle global
O toggle `features.datadog.enabled` DEVE (MUST) controlar apenas a exportação de métricas StatsD da aplicação. Com os perfis `aws` e `datadog` ativos, o valor do parâmetro DEVE determinar se `management.statsd.metrics.export.enabled` está habilitado. O toggle NÃO DEVE controlar o agente Datadog, o tracing ou a injeção de logs, que permanecem sob responsabilidade das variáveis de ambiente `DD_*` do deploy.

#### Scenario: Toggle habilitado com os perfis aws e datadog
- **QUANDO** os perfis `aws` e `datadog` estão ativos e `features.datadog.enabled` é `true`
- **ENTÃO** `management.statsd.metrics.export.enabled` é `true`
- **E** as métricas são exportadas pelo StatsD

#### Scenario: Toggle desabilitado com os perfis aws e datadog
- **QUANDO** os perfis `aws` e `datadog` estão ativos e `features.datadog.enabled` é `false`
- **ENTÃO** `management.statsd.metrics.export.enabled` é `false`
- **E** nenhuma métrica é exportada pelo StatsD

#### Scenario: Perfil datadog sem perfil aws mantém o comportamento anterior
- **QUANDO** apenas o perfil `datadog` está ativo
- **ENTÃO** `management.statsd.metrics.export.enabled` permanece `true` por padrão
- **E** o comportamento anterior a esta mudança é preservado

#### Scenario: Nenhum perfil de observabilidade está ativo
- **QUANDO** nem `aws` nem `datadog` estão ativos
- **ENTÃO** `management.statsd.metrics.export.enabled` permanece `false`

#### Scenario: Toggle não altera tracing ou injeção de logs
- **QUANDO** o valor de `features.datadog.enabled` é alterado
- **ENTÃO** as variáveis `DD_TRACE_ENABLED`, `DD_LOGS_INJECTION` e a configuração do agente permanecem inalteradas
- **E** somente a exportação de métricas é afetada

### Requirement: Manter o toggle global e aplicado na inicialização
O toggle DEVE (MUST) ser único por ambiente e compartilhado por todos os serviços que importam o caminho comum. O valor DEVE ser lido na inicialização, sem atualização dinâmica em tempo de execução.

#### Scenario: Todos os serviços leem o mesmo parâmetro por ambiente
- **QUANDO** os serviços `pedido-service`, `estoque-service` e `api-gateway` iniciam no mesmo ambiente
- **ENTÃO** todos leem `features.datadog.enabled` do mesmo caminho comum do ambiente
- **E** não existe parâmetro de toggle por serviço

#### Scenario: Alteração do parâmetro exige reinício
- **QUANDO** o valor do parâmetro é alterado com os serviços em execução
- **ENTÃO** o comportamento dos serviços não muda até que sejam reiniciados
- **E** após o reinício os serviços passam a usar o novo valor
