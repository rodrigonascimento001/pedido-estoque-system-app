# Collection Insomnia

Importe `pedido-estoque-insomnia.json` no Insomnia usando **Import > From File**.

Use o environment `Base Environment` (gateway em `http://localhost:8080`). O gateway exige um access token JWT válido emitido pelo Keycloak local; o valor inicial da variável `token` está vazio.

## Login local

1. Suba primeiro a infraestrutura; ela inicia o Keycloak em `http://localhost:8180` e importa o realm `pedido-estoque`. O Admin Console fica em `http://localhost:8180/admin/`. O usuário administrativo padrão é `admin`; a senha inicial padrão é `local-only-change-me`, a menos que `KEYCLOAK_ADMIN_USERNAME` e `KEYCLOAK_ADMIN_PASSWORD` tenham sido alteradas no `.env` da infraestrutura. No realm `pedido-estoque`, autentique-se com um usuário do realm, não com o administrador do console.
2. Abra a requisição **00 - Login Keycloak > Obter access token (Authorization Code + PKCE)** e clique em **Fetch Tokens** na aba **Auth**. Ela já está configurada com o client público `pedido-estoque-local`, scope `openid`, PKCE `S256`, os endpoints do realm e redirect URI `https://app.insomnia.rest/oauth/redirect`.
3. Faça login na janela do navegador com seu usuário do realm e autorize o retorno ao Insomnia. Se for o primeiro login de uma conta criada por `POST /api/usuarios`, o Keycloak pode exigir a ação **VERIFY_PROFILE**: preencha nome e sobrenome para concluir. O access token aparecerá no campo **Access Token** da aba **Auth**. Copie-o para `token` no environment **Base Environment**.
4. As chamadas de negócio enviam `Authorization: Bearer {{ _.token }}`. O token deve ter sido emitido pelo realm `pedido-estoque` e conter a audience `pedido-estoque-api`.

Execute `00 - Saúde e preparação` para cadastrar os dois produtos. O endpoint `POST /api/estoque` soma a quantidade ao saldo atual, então repetir a chamada adiciona mais unidades.

Depois execute `01 - Fluxo feliz`: crie o pedido, copie o `id` retornado para `pedido_sucesso` no environment e repita a consulta até `status` ficar `CONFIRMADO`. Confira que o saldo do produto caiu de 10 para 8.

Para exercitar a compensação, use `02 - Saldo insuficiente`: crie o pedido, copie o `id` para `pedido_cancelado` e consulte até `status` ficar `CANCELADO`. O produto desse cenário deve continuar com saldo 1.

Os pedidos começam como `PENDENTE` porque estoque e pedido conversam de forma assíncrona pelo Kafka. Todas as chamadas de negócio passam pelo gateway e usam o access token do Keycloak.

## Cadastro de usuário

Antes do login, execute **00 - Cadastro > Cadastrar usuário no Keycloak**. Edite o body com username único, e-mail e senha que atendam à política configurada no realm. O endpoint público `POST http://localhost:8080/api/usuarios` cria a conta habilitada no realm `pedido-estoque`; em sucesso, retorna `201` sem devolver a senha. Em seguida, use **00 - Login Keycloak** para obter o token e chamar as rotas protegidas.

Para subir o serviço de cadastro, configure `KEYCLOAK_USER_REGISTRATION_CLIENT_SECRET` com o mesmo secret nos `.env` locais da aplicação e da infraestrutura. O segredo pertence ao cliente técnico `pedido-estoque-user-registration`; não é a senha do administrador e não deve ser salvo na collection nem commitado.