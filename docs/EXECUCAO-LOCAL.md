# Subir o projeto localmente

O ambiente local usa dois projetos Docker Compose coordenados. Suba primeiro a infraestrutura, que cria a rede compartilhada `pedido-estoque-network` e fornece Kafka, bancos de dados, Redis, Keycloak e o Datadog Agent. Depois suba os serviços da aplicação.

Os comandos abaixo usam Git Bash. Abra uma janela do Git Bash para cada repositório e execute os passos na ordem indicada.

## 1. Configure os arquivos `.env` (na primeira execução)

No repositório de infraestrutura, crie o `.env` a partir do exemplo, se ainda não existir, e configure a chave do Datadog:

```bash
cd /c/Users/rodrigo.nascimento/Desktop/development/repos/pedido-estoque-system-infra
[ -f .env ] || cp .env.example .env
notepad.exe .env
```

Defina `DD_API_KEY`, confirme o `DD_SITE` da sua conta e troque `KEYCLOAK_ADMIN_PASSWORD`. Defina também `KEYCLOAK_USER_REGISTRATION_CLIENT_SECRET` com um secret local aleatório para o cliente técnico do cadastro. Use o mesmo valor no `.env` da aplicação. Mantenha esses valores somente nos `.env` locais; não os envie ao Git.

Na aplicação, o `.env` é opcional para subir os serviços com as configurações padrão. Para habilitar o envio de telemetria ao Agent, crie ou edite o `.env` da aplicação e use:

```dotenv
SPRING_PROFILES_ACTIVE=datadog
DD_AGENT_HOST=datadog-agent
DD_TRACE_ENABLED=true
```

Para usar segredos e o toggle do Datadog vindos da AWS local (LocalStack), use
`SPRING_PROFILES_ACTIVE=aws` ou `aws,datadog` e mantenha as variáveis
`SPRING_CLOUD_AWS_*` do `.env.example`.

O `.env.example` da aplicação contém as demais configurações locais. Copie-o apenas se precisar criar o `.env`:

```bash
cd /c/Users/rodrigo.nascimento/Desktop/development/projetos/pedido-estoque-system
[ -f .env ] || cp .env.example .env
notepad.exe .env
```

No `.env` da aplicação, configure `KEYCLOAK_USER_REGISTRATION_CLIENT_SECRET` com o mesmo valor definido no `.env` da infraestrutura. Esse secret autentica somente o serviço de cadastro no Admin API do realm; não é a senha do administrador do Keycloak.

## 2. Suba a infraestrutura

Em uma janela do Git Bash, no repositório de infraestrutura:

```bash
cd /c/Users/rodrigo.nascimento/Desktop/development/repos/pedido-estoque-system-infra
docker compose --profile observability up -d
docker compose ps
```

O perfil `observability` inclui o Datadog Agent; Keycloak faz parte do stack padrão. Aguarde Kafka, Redis, Postgres e Keycloak ficarem saudáveis. Acompanhe os logs, se necessário:

```bash
docker compose logs -f
```

### 2.1 Opcional: segredos e toggle via LocalStack

Para exercitar o perfil `aws` localmente, suba também o LocalStack. O profile dedicado já cria os segredos e o parâmetro de forma idempotente:

```bash
docker compose --profile localstack up -d
docker compose --profile localstack ps
```

Confira os recursos:

```bash
docker compose --profile localstack exec -T localstack awslocal secretsmanager list-secrets
docker compose --profile localstack exec -T localstack awslocal ssm get-parameter --name /pedido-estoque/local/common/features.datadog.enabled
```

## 3. Suba a aplicação

Em outra janela do Git Bash, no repositório da aplicação:

```bash
cd /c/Users/rodrigo.nascimento/Desktop/development/projetos/pedido-estoque-system
docker compose up -d --build
docker compose ps
```

O Compose da aplicação sobe `pedido-service`, `estoque-service` e `api-gateway`. Para acompanhar um serviço específico:

```bash
docker compose logs -f estoque-service
```

Saia do acompanhamento dos logs com `Ctrl+C`; isso não para os containers.

Somente o gateway é publicado para a API (`http://localhost:8080`); Keycloak usa `http://localhost:8180`. Os serviços de cadastro, pedido e estoque não publicam portas próprias. O Actuator do gateway fica disponível apenas no loopback do host em `http://localhost:9080/actuator/health`; os demais listeners de gerenciamento continuam internos.

## 4. Cadastrar usuário e testar o fluxo autenticado

O cadastro é público: `POST /api/usuarios` não exige token, mas passa pelo gateway, que aplica o rate limit por IP (5 requisições/s, burst 10) antes de encaminhar ao `user-registration-service`. O serviço usa o cliente técnico confidencial `pedido-estoque-user-registration` (client credentials) no Admin API do realm `pedido-estoque`; confirme que `KEYCLOAK_USER_REGISTRATION_CLIENT_SECRET` tem o mesmo valor nos `.env` de aplicação e infraestrutura e que o bootstrap do cliente já rodou com a infraestrutura no ar. O bootstrap também associa ao cliente o client scope `kc-admin-api` e a role `manage-users` do `realm-management`, exigidos pelo Admin API no Keycloak 26.8. Se o secret mudou depois de o serviço subir, recrie o container para ele usar o valor novo:

```bash
docker compose up -d --force-recreate --no-deps user-registration-service
```

No Git Bash, faça o cadastro pelo gateway:

```bash
curl -i -X POST http://localhost:8080/api/usuarios \
  -H 'Content-Type: application/json' \
  -d '{"username":"novo.usuario","email":"novo.usuario@example.com","password":"SenhaLocal@2026"}'
```

Troque os dados e a senha por valores que atendam à política do realm; não use essa senha em ambiente público. O sucesso retorna `201 Created` com `{"id":"<uuid>","username":"novo.usuario"}` — sem senha, token ou segredo na resposta. A conta já nasce habilitada e sem roles de negócio; não há verificação de e-mail nesta etapa.

Para conferir os erros controlados, repita a mesma chamada e teste:

```bash
# username/e-mail já cadastrado: 409 com code=user_already_exists
curl -i -X POST http://localhost:8080/api/usuarios \
  -H 'Content-Type: application/json' \
  -d '{"username":"novo.usuario","email":"novo.usuario@example.com","password":"SenhaLocal@2026"}'

# campo ausente/inválido: 400 com code=invalid_registration e fields
curl -i -X POST http://localhost:8080/api/usuarios \
  -H 'Content-Type: application/json' \
  -d '{"username":"outro.usuario","email":"invalido","password":"123"}'
```

Se o Keycloak estiver fora do ar, a resposta é `503` com `code=identity_provider_unavailable`; o serviço não informa sucesso nesse caso. A rota `GET /api/usuarios` continua exigindo JWT — somente o `POST` é anônimo.

Depois de cadastrar, obtenha o access token com **00 - Login Keycloak > Obter access token (Authorization Code + PKCE)** na [collection Insomnia](../insomnia/README.md), usando a conta recém-criada, e copie o token para a variável `token` do Base Environment. Se for o primeiro login, o Keycloak pode exigir a ação **VERIFY_PROFILE**: preencha nome e sobrenome antes de autorizar o retorno. A collection também tem o atalho **00 - Cadastro > Cadastrar usuário no Keycloak**. Com o token, chame uma rota protegida:

```bash
TOKEN='<access token obtido pelo PKCE>'

curl -i http://localhost:8080/api/estoque/11111111-1111-1111-1111-111111111111 \
  -H "Authorization: Bearer $TOKEN"

# sem o header, a mesma rota retorna 401
curl -i http://localhost:8080/api/estoque/11111111-1111-1111-1111-111111111111
```

A sequência completa é **cadastro → login PKCE → chamada autenticada**. O fluxo de pedidos descrito no [README](../README.md#testando-o-fluxo) usa o mesmo `TOKEN`.

## 5. Parar os serviços

Execute `docker compose down` em cada repositório, primeiro na aplicação e depois na infraestrutura. Use a janela do Git Bash correspondente a cada repositório:

```bash
# No repositório da aplicação
cd /c/Users/rodrigo.nascimento/Desktop/development/projetos/pedido-estoque-system
docker compose down

# No repositório de infraestrutura
cd /c/Users/rodrigo.nascimento/Desktop/development/repos/pedido-estoque-system-infra
docker compose down
```

Evite `docker compose down -v` no uso diário: a opção `-v` remove volumes e pode apagar os dados locais persistidos.
