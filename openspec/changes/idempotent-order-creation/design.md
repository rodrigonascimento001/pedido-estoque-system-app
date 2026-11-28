## Context

O fluxo atual é: gateway valida o JWT e encaminha `POST /api/pedidos` ao `pedido-service`, que grava `Pedido` PENDENTE e o evento `PedidoCriadoEvent` no outbox numa única transação e responde `201`. Nada identifica uma requisição repetida. O `pedido-service` não conhece o usuário autenticado: o JWT é validado só no gateway e as portas dos serviços não são publicadas no host.

## Goals / Non-Goals

**Goals**
- Uma repetição da mesma requisição, dentro da janela de retenção, devolve o mesmo pedido sem criar outro, inclusive sob concorrência.
- Chave reutilizada com corpo diferente é rejeitada, não aceita em silêncio.
- Um usuário não consegue ler nem colidir com a chave de outro.
- Garantia mantida mesmo se o processo cair a qualquer momento (sem estado em memória).

**Non-Goals**
- Chave obrigatória, dono do pedido, filtro de leitura por usuário e idempotência de outras rotas.

## Decisions

### D1. Registro de chaves em tabela própria, na mesma transação do pedido

Tabela `idempotency_key(scope, idem_key, request_hash, pedido_id, created_at, expires_at)` com chave primária `(scope, idem_key)`. O fluxo dentro de `@Transactional` é:

1. `INSERT ... ON CONFLICT (scope, idem_key) DO NOTHING`.
2. Se inseriu, criar o pedido e o evento no outbox e gravar o `pedido_id` na linha.
3. Se não inseriu, ler a linha existente, comparar `request_hash` e devolver o pedido guardado.

Se a criação falhar, a transação desfaz a linha da chave e o cliente pode repetir. Alternativa descartada: guardar a chave na tabela `pedidos`. Misturaria um conceito de API com o agregado e dificultaria a expiração.

### D2. Concorrência resolvida pelo banco

No Postgres, um `INSERT ... ON CONFLICT DO NOTHING` concorrente espera a primeira transação terminar. Se ela confirmar, o segundo insert não insere e cai no passo 3, já enxergando o pedido. Se ela desfizer, o segundo insere e cria o pedido. Não é preciso lock explícito, estado `EM_PROCESSAMENTO` nem resposta `409` por requisição em andamento. Alternativa descartada: lock em memória ou Redis, que não sobrevive a réplicas nem a queda do processo.

### D3. Resposta de repetição

Repetição devolve o mesmo status `201`, o mesmo `Location` e o corpo do pedido, acrescentando o header `Idempotent-Replayed: true`. O corpo reflete o pedido no momento da leitura (o `status` pode ter avançado para CONFIRMADO/CANCELADO), não um retrato congelado da primeira resposta. Isso evita guardar a resposta e deixa o cliente ver o estado real. Alternativa descartada: armazenar o corpo original, mais pesado e com valor baixo.

### D4. Comparação do corpo por hash

`request_hash` é o SHA-256 da serialização canônica do `PedidoRequest` (ordem dos itens é significativa). Diferença de hash com a mesma chave resulta em `422` com mensagem segura. O hash não guarda o corpo, então não persiste dados do pedido fora das tabelas de negócio.

### D5. Escopo por usuário via header interno

Escopo = `sub` do JWT. O gateway adiciona `X-Authenticated-User` com o `sub` validado e remove qualquer header com esse nome vindo do cliente. O `pedido-service` só aceita a chave quando o header interno existe; sem ele responde `400`, porque sem escopo uma chave de um usuário poderia devolver o pedido de outro. Isso depende de os serviços ficarem inalcançáveis fora da rede Docker, o que a change `api-security-keycloak` já garante. Alternativa descartada: validar o JWT também no `pedido-service`, que duplicaria configuração do Keycloak. Pode voltar na change de autorização (A1).

### D6. Formato da chave, retenção e limpeza

Chave com 1 a 128 caracteres ASCII imprimíveis, sem espaços (UUID recomendado); fora disso, `400`. Retenção de 24 horas (`app.idempotency.ttl`), com `expires_at` gravado na criação. Uma linha expirada se comporta como inexistente. Um job `@Scheduled` apaga linhas expiradas em lotes. Com o job desligado, uma linha expirada ainda não removida é tratada no insert, que a substitui se `expires_at` já passou.

### D7. Observabilidade

Contadores `pedido.idempotency.replay` e `pedido.idempotency.conflict` (corpo diferente), sem a chave nem o usuário como tag. O log da repetição leva `pedido.id`, nunca o valor da chave.

## Risks / Trade-offs

- **Dependência do gateway:** se alguém publicar a porta 8081 no host, o header interno pode ser forjado. Mitigação: a rede privada já é requisito da change de segurança; registrar o risco no `AGENTS.md`.
- **Crescimento da tabela:** mitigado por TTL e job de limpeza; volume proporcional aos pedidos de 24 h.
- **Chave opcional:** clientes que não enviam o header continuam sem proteção. Aceito nesta change; a obrigatoriedade fica para depois.
- **Substituição de linha expirada** exige cuidado com concorrência: coberta por teste.

## Migration Plan

1. Migration `V3__idempotency_key.sql` cria a tabela e o índice em `expires_at`; não altera dados existentes.
2. Publicar gateway e `pedido-service` juntos. Se o `pedido-service` subir antes do gateway novo, requisições com chave recebem `400` até o gateway atualizar, e requisições sem chave seguem normais.
3. Rollback: reverter o código; a tabela pode ficar sem uso.

## Open Questions

- Janela de retenção de 24 h está adequada para os clientes previstos?
- Devolver `201` na repetição (proposto) ou `200`? Escolhi `201` por compatibilidade com clientes que tratam só `201` como sucesso.
- Nome do header interno (`X-Authenticated-User`) deve ser reaproveitado pela change de autorização?
