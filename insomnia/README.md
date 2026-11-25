# Collection Insomnia

Importe `pedido-estoque-insomnia.json` no Insomnia usando **Import > From File**.

Use o environment `Base Environment` (gateway em `http://localhost:8080`). O gateway exige um access token JWT válido emitido pelo Keycloak local; o valor inicial da variável `token` está vazio.

## Login local

1. Suba primeiro a infraestrutura; ela inicia o Keycloak em `http://localhost:8180` e importa o realm `pedido-estoque`. Consulte o README do repositório de infraestrutura para configurar a senha administrativa e criar um usuário local.
2. Em uma requisição da collection, abra **Auth**, selecione **OAuth 2.0** e configure Authorization Code, client ID `pedido-estoque-local`, scope `openid`, PKCE `S256`, authorize URL `http://localhost:8180/realms/pedido-estoque/protocol/openid-connect/auth`, token URL `http://localhost:8180/realms/pedido-estoque/protocol/openid-connect/token` e redirect URI `https://app.insomnia.rest/oauth/redirect`. Deixe o client secret vazio.
3. Conclua o login no navegador, copie o access token emitido e cole na variável `token` do environment Base Environment. As requisições da collection enviam `Authorization: Bearer {{ _.token }}`.

Execute `00 - Saúde e preparação` para cadastrar os dois produtos. O endpoint `POST /api/estoque` soma a quantidade ao saldo atual, então repetir a chamada adiciona mais unidades.

Depois execute `01 - Fluxo feliz`: crie o pedido, copie o `id` retornado para `pedido_sucesso` no environment e repita a consulta até `status` ficar `CONFIRMADO`. Confira que o saldo do produto caiu de 10 para 8.

Para exercitar a compensação, use `02 - Saldo insuficiente`: crie o pedido, copie o `id` para `pedido_cancelado` e consulte até `status` ficar `CANCELADO`. O produto desse cenário deve continuar com saldo 1.

Os pedidos começam como `PENDENTE` porque estoque e pedido conversam de forma assíncrona pelo Kafka. Todas as chamadas de negócio passam pelo gateway e usam o access token do Keycloak.
