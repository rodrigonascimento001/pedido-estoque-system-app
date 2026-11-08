# Collection Insomnia

Importe `pedido-estoque-insomnia.json` no Insomnia usando **Import > From File**.

Use o environment `Base Environment` (gateway em `http://localhost:8080`, token local `dev-token`). Execute primeiro `00 - Saúde e preparação` para cadastrar os dois produtos. O endpoint `POST /api/estoque` soma a quantidade ao saldo atual, então repetir a chamada adiciona mais unidades.

Depois execute `01 - Fluxo feliz`: crie o pedido, copie o `id` retornado para `pedido_sucesso` no environment e repita a consulta até `status` ficar `CONFIRMADO`. Confira que o saldo do produto caiu de 10 para 8.

Para exercitar a compensação, use `02 - Saldo insuficiente`: crie o pedido, copie o `id` para `pedido_cancelado` e consulte até `status` ficar `CANCELADO`. O produto desse cenário deve continuar com saldo 1.

Os pedidos começam como `PENDENTE` porque estoque e pedido conversam de forma assíncrona pelo Kafka. Todas as chamadas de negócio passam pelo gateway e usam o bearer de desenvolvimento, que valida apenas o formato do header.
