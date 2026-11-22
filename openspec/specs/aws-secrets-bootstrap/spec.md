# aws-secrets-bootstrap

## Purpose

Definir como os serviços carregam segredos de banco do AWS Secrets Manager no perfil aws, preservando a execução local sem AWS.

## Requirements
### Requirement: Carregar segredos de banco do AWS Secrets Manager por perfil
Sob o perfil `aws`, cada serviço que possui banco de dados DEVE (MUST) importar explicitamente o seu segredo via `spring.config.import`. O segredo DEVE conter os campos `AWS_DB_URL`, `AWS_DB_USER` e `AWS_DB_PASSWORD`, com nomes que não colidam com as variáveis de ambiente `DB_*` usadas no fluxo local. Fora do perfil `aws`, o serviço NÃO DEVE realizar chamadas ao AWS e DEVE manter o comportamento atual baseado em variáveis de ambiente e `application.yml`.

#### Scenario: Serviço com banco inicia com o perfil aws ativo
- **QUANDO** o perfil `aws` está ativo e o segredo do serviço existe no Secrets Manager
- **ENTÃO** o serviço importa o segredo antes da criação do `DataSource`
- **E** a URL, o usuário e a senha do banco são resolvidos a partir dos campos do segredo

#### Scenario: Serviço inicia sem o perfil aws
- **QUANDO** o perfil `aws` não está ativo
- **ENTÃO** o serviço não executa nenhuma importação do Secrets Manager
- **E** continua usando as variáveis de ambiente e os valores de `application.yml`

#### Scenario: Serviço sem banco não importa segredo de banco
- **QUANDO** o `api-gateway` inicia com o perfil `aws`
- **ENTÃO** ele importa apenas os parâmetros globais
- **E** não tenta importar nenhum segredo de banco de dados

### Requirement: Falhar rápido quando um segredo obrigatório estiver indisponível
Sob o perfil `aws`, a importação de segredos NÃO DEVE ser opcional. A ausência do segredo ou a falta de permissão de acesso DEVE (MUST) impedir a inicialização do serviço com uma mensagem que identifique o segredo problemático. O serviço NÃO DEVE iniciar com credenciais padrão quando o perfil `aws` estiver ativo.

#### Scenario: Segredo ausente impede a inicialização
- **QUANDO** o perfil `aws` está ativo e o segredo configurado não existe
- **ENTÃO** o serviço falha ao iniciar
- **E** a mensagem de erro identifica o segredo que não foi encontrado

#### Scenario: Permissão de IAM insuficiente impede a inicialização
- **QUANDO** o perfil `aws` está ativo e a credencial não possui permissão para ler o segredo
- **ENTÃO** o serviço falha ao iniciar
- **E** o erro diferencia a falha de acesso da ausência do segredo

#### Scenario: Credenciais padrão não são usadas sob o perfil aws
- **QUANDO** o perfil `aws` está ativo e o segredo existe mas não contém todas as chaves obrigatórias
- **ENTÃO** o serviço falha ao iniciar
- **E** não recorre aos valores padrão `postgres` de `application.yml`

### Requirement: Preservar o fallback local e o build sem AWS
O build padrão e a execução local sem o perfil `aws` DEVEM (MUST) continuar funcionando sem credenciais, sem rede para a AWS e sem LocalStack. Os testes existentes NÃO DEVEM ser alterados para depender de AWS.

#### Scenario: Build padrão executa sem credenciais AWS
- **QUANDO** `mvn -B verify` é executado sem variáveis de credencial AWS
- **ENTÃO** todo o build e os testes existentes passam
- **E** nenhuma chamada à AWS é realizada

#### Scenario: Desenvolvedor executa a aplicação local sem perfil aws
- **QUANDO** a aplicação é iniciada com o perfil padrão
- **ENTÃO** ela usa as variáveis de ambiente e os valores padrão de `application.yml`
- **E** o comportamento é idêntico ao anterior a esta mudança
