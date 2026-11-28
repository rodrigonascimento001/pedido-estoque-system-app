## Why

Hoje uma pessoa precisa ser criada manualmente no Keycloak antes de conseguir fazer login e usar a API. Um fluxo de cadastro integrado ao realm `pedido-estoque` permitirá que novos usuários criem suas próprias credenciais e entrem no fluxo de autenticação já existente.

## What Changes

- Criar um serviço de cadastro de usuários com uma API pública para registrar username, e-mail e senha no Keycloak.
- Fazer o serviço provisionar usuários no realm `pedido-estoque` por meio do Admin API, usando uma identidade técnica com permissões mínimas e configuráveis.
- Definir validação de entrada, resposta que não exponha a senha, tratamento de username/e-mail duplicados e comunicação segura entre serviço e Keycloak.
- Integrar o novo serviço à rede Docker e ao gateway para que o cadastro possa ser chamado antes do login; manter o login e a validação de JWT existentes.
- Documentar a configuração local do serviço e o fluxo cadastro → login → chamada autenticada à API.

## Capabilities

### New Capabilities

- `keycloak-user-registration`: criação segura de usuários no realm da aplicação e disponibilidade das credenciais para o fluxo de login existente.

### Modified Capabilities

None.

## Impact

- Novo módulo/serviço da aplicação, rota de cadastro no gateway e configuração do Compose.
- Repositório `pedido-estoque-system-infra`: configuração de um cliente confidencial de serviço para Admin API, com permissões mínimas no realm `pedido-estoque`; sem credenciais administrativas versionadas.
- Documentação local e coleção Insomnia para demonstrar cadastro e autenticação.
- O serviço precisará receber username, e-mail e senha; a senha será tratada como dado secreto e nunca retornada, registrada em log ou persistida pela aplicação.
