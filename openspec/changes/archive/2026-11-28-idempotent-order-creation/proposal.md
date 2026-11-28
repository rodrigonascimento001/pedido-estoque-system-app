## Why

`POST /api/pedidos` cria um pedido novo a cada chamada. Se o cliente não recebe a resposta (timeout, queda de rede, duplo clique) e repete a requisição, o sistema cria dois pedidos e reserva estoque duas vezes. A change `harden-order-kafka-consistency` protege contra evento Kafka duplicado para o mesmo `pedidoId`, mas não enxerga duas requisições HTTP separadas, porque cada uma gera um `pedidoId` diferente.

## What Changes

- Aceitar o header opcional `Idempotency-Key` em `POST /api/pedidos`. Com a chave, uma repetição da mesma requisição devolve o pedido já criado em vez de criar outro.
- Guardar a chave, um hash do corpo e o `pedidoId` resultante na mesma transação que cria o pedido e grava o evento no outbox, de modo que não exista pedido sem chave nem chave sem pedido.
- Responder `422 Unprocessable Entity` quando a mesma chave chega com um corpo diferente, e tratar requisições concorrentes com a mesma chave sem criar dois pedidos.
- Isolar as chaves por usuário autenticado. O gateway passa a repassar o `sub` do JWT validado em um header interno, removendo qualquer valor enviado pelo cliente.
- Expirar as chaves após 24 horas, com limpeza periódica.
- Registrar métricas de repetições e de conflitos, e atualizar a documentação e a coleção Insomnia.

Sem o header, o comportamento atual é mantido.

## Capabilities

### New Capabilities

- `idempotent-order-creation`: criação de pedido segura para repetição de requisição HTTP, com escopo por usuário e retenção limitada.

### Modified Capabilities

None.

## Impact

- `pedido-service`: nova tabela `idempotency_key` (migration `V3`), serviço de idempotência, ajuste em `PedidoController`/`PedidoService`, tratamento de erro e métricas.
- `api-gateway`: filtro que repassa a identidade autenticada em header interno e descarta o header se vier do cliente.
- Documentação: `docs/arquitetura.md` (sequência de criação), README, `insomnia/README.md` e a coleção.
- Sem mudança nos eventos Kafka nem no `estoque-service`. Sem quebra de contrato: o header é opcional.
- Fica como base para a futura autorização (backlog A1), que também precisa saber o usuário dono do pedido.

## Non-goals

- Tornar a chave obrigatória (pode virar uma change própria depois de os clientes adotarem o header).
- Associar o pedido ao usuário ou filtrar leituras por dono (backlog A1).
- Idempotência em outras operações de escrita.
