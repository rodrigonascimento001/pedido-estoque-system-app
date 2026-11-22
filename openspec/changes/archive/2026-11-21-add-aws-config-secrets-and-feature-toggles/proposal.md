## Why

Hoje os serviços leem configuração de variáveis de ambiente e `application.yml`, as credenciais de banco possuem valores padrão inseguros (`postgres`) e alterar a observabilidade exige editar perfis/variáveis e refazer o deploy. Precisamos de uma fonte centralizada de segredos e de uma forma de alternar comportamentos da aplicação globalmente sem tocar nos arquivos de deploy.

## What Changes

- Adicionar Spring Cloud AWS ao módulo compartilhado `common` para que todos os serviços herdem o suporte a Secrets Manager e Parameter Store.
- Carregar as credenciais de banco do AWS Secrets Manager por meio de entradas explícitas de `spring.config.import` em cada serviço, condicionadas ao perfil Spring `aws`.
- Carregar feature toggles globais do AWS Parameter Store pelo mesmo perfil, sendo o primeiro toggle `features.datadog.enabled`.
- Introduzir o toggle de observabilidade do Datadog (decisão C2): o parâmetro habilita/desabilita a exportação de métricas StatsD do Micrometer no lado da aplicação. Tracing, injeção de logs e o ciclo de vida do agente Datadog continuam controlados pelas variáveis de ambiente `DD_*` do deploy.
- Aplicar uma política de falha condicionada ao perfil: sem o perfil `aws`, o comportamento permanece inalterado (variáveis de ambiente/valores de `yml`); com o perfil `aws`, segredos ausentes falham rapidamente e toggles ausentes usam valores seguros.
- Provisionar LocalStack (Secrets Manager + SSM) no repositório de infraestrutura por trás de um profile de Compose `localstack`, semeado de forma idempotente com segredos e parâmetros locais.
- Cobrir a integração com testes Testcontainers LocalStack que não exigem AWS real e não alteram os testes existentes nem a pipeline `mvn -B verify`.
- **BREAKING** apenas no perfil `aws`: os serviços não conseguem mais iniciar sem valores acessíveis no Secrets Manager. O perfil padrão mantém o comportamento atual.

## Capacidades

### Novas Capacidades

- `aws-secrets-bootstrap`: bootstrap de segredos da aplicação a partir do AWS Secrets Manager condicionado ao perfil, incluindo imports explícitos por serviço, comportamento de falha rápida e fallback por variáveis de ambiente fora do perfil `aws`.
- `aws-feature-toggles`: feature toggles globais lidos do AWS Parameter Store com valores seguros, incluindo a semântica do toggle de observabilidade do Datadog.
- `local-aws-emulation`: emulação local de Secrets Manager e Parameter Store com LocalStack no repositório de infraestrutura, com dados de seed idempotentes para desenvolvimento local e testes.

### Capacidades Modificadas

Nenhuma. Os requisitos existentes de `separated-compose-stacks` e `automated-ci-image-publishing` continuam válidos; o LocalStack se encaixa no contrato do stack de infraestrutura e a CI deve continuar funcionando sem AWS.

## Impacto

- Módulo `common`: novas dependências (Spring Cloud AWS e AWS SDK v2 transitivamente) e convenções compartilhadas de configuração para segredos e toggles.
- `pedido-service` e `estoque-service`: imports explícitos em `application-aws.yml` e placeholders de datasource alimentados pelo Secrets Manager.
- `api-gateway`: herda a configuração compartilhada de toggles; não possui segredo de banco.
- `pom.xml` raiz: gerenciamento de dependências do Spring Cloud AWS.
- Repositório de infraestrutura: serviço LocalStack, scripts de seed, `.env.example` e atualizações de documentação.
- Testes: nova cobertura com Testcontainers LocalStack; testes existentes e build padrão permanecem independentes de AWS.
- Sem mudanças nas APIs HTTP, nos contratos de eventos Kafka ou no comportamento padrão atual (não-AWS).
- Autenticação de Kafka e Redis ficam explicitamente fora de escopo e são adiadas para uma mudança futura.
