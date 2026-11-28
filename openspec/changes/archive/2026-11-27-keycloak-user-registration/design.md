## Context

O gateway já valida access tokens do realm `pedido-estoque` (issuer local `http://localhost:8180/realms/pedido-estoque`, audience `pedido-estoque-api`). O login existente usa Authorization Code + PKCE no cliente público `pedido-estoque-local`. A infraestrutura Keycloak e a aplicação rodam em Compose separados ligados à rede `pedido-estoque-network`.

Hoje o cadastro de usuários é manual no console do Keycloak. O novo fluxo precisa aceitar um cadastro antes de o usuário possuir token, criar a conta no realm correto e permitir que ela entre no login já existente. A aplicação não deve usar nem receber as credenciais do administrador do Keycloak.

## Goals / Non-Goals

**Goals:**

- Disponibilizar `POST /api/usuarios` para criação de conta com username, e-mail e senha.
- Criar o usuário habilitado no realm `pedido-estoque`, de modo que possa iniciar o login Authorization Code + PKCE.
- Isolar as credenciais técnicas do Admin API no serviço de cadastro e limitar a permissão ao gerenciamento de usuários do realm da aplicação.
- Evitar vazamento da senha em respostas, logs, métricas, traces e armazenamento local.
- Permitir chamadas de cadastro sem JWT, sem tornar públicas as demais rotas da API.

**Non-Goals:**

- Implementar tela de cadastro ou alterar o cliente OAuth/fluxo de login.
- Atribuir roles de negócio, conceder privilégios administrativos, redefinir senha, atualizar perfil ou excluir usuários.
- Enviar e-mail de verificação, fazer recuperação de conta ou implementar MFA nesta change.
- Criar contas no realm `master` ou aceitar credenciais administrativas do usuário solicitante.

## Decisions

- **Serviço dedicado `user-registration-service`**: adicionar um módulo Spring Boot separado para concentrar validação, integração administrativa com Keycloak e tratamento de erros. A alternativa de colocar a integração diretamente no gateway foi rejeitada para manter o gateway como limite de autenticação/roteamento e evitar misturar acesso privilegiado ao Admin API com validação de tokens de negócio.
- **Cadastro público e rota isolada**: o gateway permitirá sem JWT somente `POST /api/usuarios`; todos os demais métodos e caminhos continuam exigindo JWT como hoje. Aplicar o rate limit existente por IP à rota pública para reduzir abuso. O serviço não terá porta publicada no host e receberá tráfego apenas pela rede privada do Compose.
- **Contrato HTTP inicial**: `POST /api/usuarios` recebe `username`, `email` e `password`; sucesso retorna `201 Created` com identificador/username e sem senha. Username e e-mail duplicados retornam `409 Conflict`; campos ausentes ou inválidos retornam `400 Bad Request`; falhas de dependência não são apresentadas como sucesso.
- **Provisionamento pelo Admin API**: o serviço usa um cliente OAuth confidencial separado, com service account e client credentials, no realm `pedido-estoque`. A configuração da infraestrutura concede somente a role `manage-users` de `realm-management` e associa o client scope `kc-admin-api`, exigido pelo Admin API no Keycloak 26.8; não reutiliza o usuário `admin` nem seu password. Client ID e secret são configuração externa (variáveis locais ignoradas pelo Git; secrets manager no ambiente que o oferecer).
- **Senha e estado inicial**: enviar a senha como credencial não temporária ao criar o usuário, para permitir login imediato. A política de senha do realm é a autoridade para requisitos de senha; retornar mensagens seguras para violações. Não armazenar senha nem registrar corpos de requisição. A conta começa habilitada e sem roles adicionais; o primeiro login pode exigir a ação padrão `VERIFY_PROFILE` do Keycloak, documentada no fluxo local.
- **Sem verificação de e-mail nesta etapa**: cadastro não exige SMTP ou confirmação externa. Essa decisão mantém o primeiro fluxo local funcional e deixa verificação de contato como evolução separada; implantação pública deve reavaliar abuso e confirmação de identidade.
- **Erros do Keycloak**: mapear conflitos conhecidos para `409`, erros de validação para `400` e indisponibilidade/erros inesperados para resposta controlada `5xx`, sem repassar conteúdo interno, tokens ou segredos do Admin API.
- **Configuração de rede**: adicionar `user-registration-service` ao Compose da aplicação com URL interna do Keycloak (`http://keycloak:8080`) e configuração do Admin API/service account. A URI externa do issuer continua sendo `http://localhost:8180/realms/pedido-estoque` nos tokens para corresponder à validação do gateway.

## Risks / Trade-offs

- [A rota pública pode ser usada para criar contas em excesso] → Rate limit por IP no gateway, validação de payload e política de senha do realm; CAPTCHA e verificação de e-mail ficam como medidas futuras se o serviço for exposto publicamente.
- [Uma credencial técnica comprometida permite alterações de contas] → Criar client confidencial dedicado com menor privilégio, não conceder `realm-admin`, não versionar o secret e limitar sua distribuição ao serviço.
- [Diferenças de resposta/erros entre versões do Keycloak podem alterar o mapeamento] → Encapsular a integração atrás de componente/cliente próprio e cobrir sucesso, conflito, validação e indisponibilidade com testes.
- [Usuários cadastrados sem e-mail verificado podem usar endereço incorreto] → Informar que o cadastro inicial não verifica e-mail e manter verificação obrigatória fora desta change até existir configuração de e-mail e UX adequadas.
- [O cliente Insomnia requer obter token pelo browser] → Documentar cadastro separado do login e provar o fluxo completo: cadastrar, obter token por Authorization Code + PKCE e acessar uma rota protegida.

## Migration Plan

1. Provisionar no realm `pedido-estoque` o cliente técnico confidencial e a permissão mínima de gerenciamento de usuários; definir seu secret por configuração local/segura.
2. Implantar `user-registration-service` no Compose de aplicação e configurar o gateway para a rota de cadastro.
3. Validar conta recém-criada com o login existente e chamar uma rota protegida com o token emitido.
4. Rollback: remover a rota e o serviço, e revogar/desabilitar o cliente técnico no Keycloak. Contas já criadas permanecem no realm e podem ser removidas pelo administrador.

## Open Questions

- Confirmar, antes de expor em ambiente público, se a política do produto exige verificação de e-mail, CAPTCHA ou bloqueio de domínios descartáveis. Para o fluxo local inicial, o cadastro cria a conta habilitada sem verificação.
- Confirmar o nome final do novo módulo/serviço com as convenções existentes antes de implementar; `user-registration-service` é o nome proposto.
