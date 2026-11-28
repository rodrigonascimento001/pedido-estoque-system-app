## Why

O gateway valida o JWT, mas qualquer token válido do realm acessa qualquer rota. Na prática, hoje:

- qualquer usuário logado lê o pedido de outro usuário, bastando conhecer o `id`;
- qualquer usuário logado cadastra ou repõe estoque em `/api/estoque`;
- o pedido não sabe quem o criou, então nada impede nem permite auditar isso.

A autenticação resolveu "quem é você". Falta definir "o que você pode fazer" e "de quem é cada pedido". Também é pré-requisito para o serviço de usuários, para o preço no servidor e para notificações, que precisam de um dono do pedido.

## What Changes

- Definir duas roles de realm no Keycloak: `customer` (cria e consulta os próprios pedidos) e `operator` (gerencia estoque e consulta qualquer pedido). Toda conta nova recebe `customer`; `operator` nunca é atribuído pelo cadastro público.
- O gateway passa a extrair as roles do token (`realm_access.roles`, claim configurável) e a aplicar uma política por rota: `/api/estoque/**` exige `operator`; `POST` e `GET` em `/api/pedidos/**` exigem `customer` ou `operator`. Token válido sem a role responde `403`.
- O gateway repassa as roles em um header interno `X-Authenticated-Roles`, ao lado do `X-Authenticated-User` já existente, e descarta qualquer valor vindo do customer.
- O `pedido-service` grava o dono do pedido (`owner_id`, o `sub` do JWT) na criação e só devolve o pedido ao dono ou a um `operator`. Quem não pode ver recebe `404`, igual a um pedido inexistente.
- Pedidos já existentes, sem dono, ficam visíveis apenas para `operator`.
- Métrica de acessos negados e documentação, incluindo como obter um usuário `operator` no ambiente local.

## Capabilities

### New Capabilities

- `role-based-authorization`: roles `customer` e `operator`, política por rota no gateway e repasse das roles aos serviços.
- `order-ownership`: dono do pedido e regra de visibilidade na leitura.

### Modified Capabilities

- `keycloak-api-security`: a política inicial "qualquer token válido é aceito, sem roles" deixa de valer.

## Impact

- `api-gateway`: conversor de roles do JWT, regras de autorização por rota, filtro que repassa `X-Authenticated-Roles`, tratamento de `403`.
- `pedido-service`: migration `V4` (coluna `owner_id`), `Pedido`, `PedidoService`, `PedidoController` e tratamento de visibilidade.
- `common`: constante do novo header interno.
- Repositório `pedido-estoque-system-infra` (fora deste repositório): criar as roles `customer` e `operator` no realm, incluir `customer` nas roles padrão e semear um usuário `operator` para desenvolvimento local, sem credenciais versionadas.
- Documentação e Insomnia: roles, usuário operator local e o novo `403`.
- **Quebra de contrato:** tokens de usuários que ainda não têm `customer` passam a receber `403`. Mitigação: o bootstrap do realm atribui `customer` às contas existentes.
- Sem mudança nos eventos Kafka nem no `estoque-service`.

## Non-goals

- Listar pedidos do usuário (`GET /api/pedidos`), que não existe hoje.
- Scopes por operação, grupos, organizações ou permissões finas por produto.
- Validar o JWT dentro dos serviços de pedido e estoque (continuam confiando no gateway e na rede privada).
- Incluir o usuário nos eventos Kafka (será feito junto com as notificações).
- Autorização por usuário no limite de taxa.
