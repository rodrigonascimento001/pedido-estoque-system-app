## Context

O gateway valida assinatura, issuer, audience e validade do JWT e depois encaminha tudo para os serviços (`anyExchange().authenticated()`). Desde a change de idempotência, ele também repassa o `sub` em `X-Authenticated-User`. O `pedido-service` não valida JWT e não guarda quem criou o pedido; o `estoque-service` não distingue quem chama. As roles e o realm vivem no repositório de infraestrutura, e o cadastro público cria contas sem roles adicionais.

## Goals / Non-Goals

**Goals**
- Impedir que um usuário leia pedido de outro.
- Restringir o cadastro e a reposição de estoque a quem opera o sistema.
- Registrar o dono do pedido para as próximas evoluções (usuário, notificações, preço).
- Manter a decisão simples de entender e de testar.

**Non-Goals**
- Permissões finas, scopes, listagem de pedidos, validação de JWT nos serviços e usuário nos eventos.

## Decisions

### D1. Duas roles de realm: `customer` e `operator`

`customer` cria e consulta os próprios pedidos. `operator` gerencia estoque e consulta qualquer pedido, mas não cria pedido em nome de outro. Toda conta nova recebe `customer` pelas roles padrão do realm; `operator` só é atribuído por quem administra o Keycloak. Alternativa descartada: uma role `admin` separada, sem uso hoje. Pode entrar depois sem quebrar nada.

### D2. Onde cada decisão é tomada

- **Gateway, política por rota** (grossa): `/api/estoque/**` exige `operator`; `/api/pedidos/**` exige `customer` ou `operator`; cadastro e health continuam como estão. Resposta `403` quando o token é válido e falta a role.
- **pedido-service, visibilidade por registro** (fina): só o gateway sabe a role, mas só o serviço sabe de quem é o pedido. O gateway repassa as roles e o serviço decide.

Alternativa descartada: o gateway consultar o serviço para saber o dono. Acoplamento e uma chamada extra por requisição.

### D3. Roles no token e no header interno

As roles vêm de `realm_access.roles` (nome do claim em `app.security.jwt.roles-claim`). O gateway as converte em authorities `ROLE_<nome>` e repassa a lista, separada por vírgula, em `X-Authenticated-Roles`, removendo qualquer valor enviado pelo customer (mesmo tratamento do `X-Authenticated-User`). A confiança nos headers continua dependendo de os serviços ficarem inalcançáveis fora da rede Docker, como já registrado no `AGENTS.md`.

### D4. Dono do pedido

Coluna `owner_id VARCHAR(255)` em `pedidos`, preenchida com o `sub` na criação. É obrigatória para pedidos novos: sem `X-Authenticated-User`, a criação responde `400`. Pedidos existentes ficam com `NULL`.

### D5. Regra de leitura e resposta `404`

`GET /api/pedidos/{id}` devolve o pedido se o `X-Authenticated-User` é o dono, ou se as roles incluem `operator`. Caso contrário responde `404`, a mesma resposta de um pedido que não existe, para não revelar quais ids existem. Pedido com dono `NULL` só é visível para `operator`. Um contador `order.access.denied` (sem usuário ou id como tag) registra as negações.

### D6. Idempotência e autorização

A chave de idempotência já é isolada pelo `sub`. Nada muda ali; a criação com chave continua exigindo o usuário.

### D7. Roles ausentes no token

Token válido sem roles de negócio vale como não autenticado para fins de rota protegida: recebe `403`. Isso inclui contas criadas antes da mudança, motivo do passo de migração.

## Risks / Trade-offs

- **Contas existentes sem `customer`:** passam a receber `403`. Mitigação: o bootstrap do realm atribui `customer` às contas existentes e às novas; documentar o passo.
- **Roles só no gateway:** se alguém publicar as portas dos serviços, os headers podem ser forjados. Risco já aceito e registrado; validar o JWT nos serviços fica para uma change futura.
- **Duas fontes da regra** (rota no gateway, dono no serviço): mitigado por testes nas duas camadas e pela documentação da divisão.
- **Dependência do repositório de infraestrutura:** as roles precisam existir antes do deploy; sem elas ninguém acessa a API.

## Migration Plan

1. No repositório de infraestrutura: criar as roles, incluir `customer` nas padrão, atribuir `customer` às contas existentes e semear um `operator` local.
2. Aplicar a migration `V4` (coluna nula, sem reescrever dados).
3. Publicar gateway e `pedido-service` juntos. Se o `pedido-service` subir primeiro, criações sem o header de usuário recebem `400` até o gateway atualizar.
4. Rollback: reverter o código; a coluna pode ficar sem uso.

## Decisions on the open questions (approved 2026-11-28)

- Os nomes das roles são `customer` e `operator`, em inglês. A partir desta change, nomes novos no código (classes, colunas, métricas, headers e roles) seguem em inglês; o que já existe em português não é renomeado nesta change.
- O `operator` consulta qualquer pedido, para suporte.
- A mudança no repositório de infraestrutura entra em PR próprio, preparado junto com a implementação.
- Contas existentes recebem `customer` automaticamente pelo bootstrap do realm.
