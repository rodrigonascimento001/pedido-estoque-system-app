# Subir o projeto localmente

O ambiente local usa dois projetos Docker Compose coordenados. Suba primeiro a infraestrutura, que cria a rede compartilhada `pedido-estoque-network` e fornece Kafka, bancos de dados, Redis e o Datadog Agent. Depois suba os serviços da aplicação.

## 1. Configure os arquivos `.env` (na primeira execução)

No repositório de infraestrutura, crie o `.env` a partir do exemplo, se ainda não existir, e configure a chave do Datadog:

```powershell
Set-Location 'C:\Users\rodrigo.nascimento\Desktop\development\repos\pedido-estoque-system-infra'
Copy-Item .env.example .env  # somente se .env ainda não existir
notepad .env
```

Defina `DD_API_KEY` e confirme o `DD_SITE` da sua conta. Mantenha a chave somente nesse `.env` local; não a envie ao Git.

Na aplicação, o `.env` é opcional para subir os serviços com as configurações padrão. Para habilitar o envio de telemetria ao Agent, crie ou edite o `.env` da aplicação e use:

```dotenv
SPRING_PROFILES_ACTIVE=datadog
DD_AGENT_HOST=datadog-agent
DD_TRACE_ENABLED=true
```

O `.env.example` da aplicação contém as demais configurações locais. Copie-o apenas se precisar criar o `.env`:

```powershell
Set-Location 'C:\Users\rodrigo.nascimento\Desktop\development\projetos\pedido-estoque-system'
Copy-Item .env.example .env  # somente se .env ainda não existir
notepad .env
```

## 2. Suba a infraestrutura

Em uma janela do PowerShell, no repositório de infraestrutura:

```powershell
Set-Location 'C:\Users\rodrigo.nascimento\Desktop\development\repos\pedido-estoque-system-infra'
docker compose --profile observability up -d
docker compose ps
```

O perfil `observability` inclui o Datadog Agent. Acompanhe os logs, se necessário:

```powershell
docker compose logs -f
```

## 3. Suba a aplicação

Em outra janela do PowerShell, no repositório da aplicação:

```powershell
Set-Location 'C:\Users\rodrigo.nascimento\Desktop\development\projetos\pedido-estoque-system'
docker compose up -d --build
docker compose ps
```

O Compose da aplicação sobe `pedido-service`, `estoque-service` e `api-gateway`. Para acompanhar um serviço específico:

```powershell
docker compose logs -f estoque-service
```

Saia do acompanhamento dos logs com `Ctrl+C`; isso não para os containers.

## 4. Parar os serviços

Execute `docker compose down` em cada repositório, primeiro na aplicação e depois na infraestrutura:

```powershell
# No repositório da aplicação
docker compose down

# No repositório de infraestrutura
docker compose down
```

Evite `docker compose down -v` no uso diário: a opção `-v` remove volumes e pode apagar os dados locais persistidos.
