# 0031. Frontend base

- Status: aceito
- Data: 2026-11-07
- Etapa: E19

## Contexto

A interface de administração precisa de login com o provedor OIDC, troca entre os tenants do usuário, chamadas tipadas à API e tratamento de erros uniforme, sobre uma base em que as telas da E20 possam ser acrescentadas.

## Decisão

**Pilha.** React 19, TypeScript estrito, Vite, Mantine (componentes e notificações), React Router 7. As versões são fixas. O mínimo do projeto é Node 22.22, 24.15 ou 26 (o Node 20 saiu de suporte; o 26 é a LTS atual e é o usado no CI e na imagem), o que permite React Router 8, jsdom 31 e vitest 5. O TypeScript fica na 5.9 porque o `openapi-typescript` 7 ainda declara `typescript ^5` como par (o `typescript-eslint` aceita até a 6.0); o 7 só virá quando os dois o suportarem.

**Login (OIDC com PKCE).** `oidc-client-ts` com código de autorização, cliente público sem segredo (PKCE S256, o padrão da biblioteca), escopo `openid profile`, retorno em `/callback` e saída com redirecionamento ao provedor. A sessão fica no `sessionStorage` (some ao fechar a aba) e é renovada com refresh token; guardar em memória seria mais restritivo, mas perderia a sessão a cada recarga. O CSP de produção (nginx) bloqueia scripts de terceiros, o que reduz o risco de um XSS ler o armazenamento. O destino depois do login só aceita caminhos internos (nunca URL externa).

**Tenant.** Depois do login a interface lê `GET /api/v1/me` (tenants e papéis) e seleciona o primeiro tenant em ordem alfabética, ou o último escolhido na aba. A troca de tenant só muda a seleção (o tenant de cada chamada vai no caminho da URL e é conferido pelo backend a cada requisição, ADR 0021); sair limpa tudo da aba. `can(papel)` segue a hierarquia do backend para esconder o que o usuário não pode fazer; a decisão de verdade continua sendo do servidor.

**Cliente da API gerado do OpenAPI.** `docs/openapi/admin-api.yaml` descreve a API de administração; `npm run gen:api` gera os tipos (`src/api/schema.d.ts`, versionado) e `openapi-fetch` os usa. Dois conferentes impedem deriva: `npm run check:api` (parte do `verify`) falha se o arquivo gerado não corresponde à especificação, e o `AdminApiSpecTest` do backend falha se a especificação não lista exatamente os endpoints dos controladores.

**Erros.** O cliente converte `application/problem+json` em `ApiError` (status, tipo, `requestId`, violações por campo, `Retry-After`). `describeError` devolve só mensagens seguras (nunca o texto de erros inesperados); `notifyError` mostra o início do `requestId` para o suporte achar a execução. 401 da API leva ao login de novo; um `ErrorBoundary` evita página em branco.

**Layout.** `AppShell` do Mantine: cabeçalho com o seletor de tenant e o menu do usuário (sair), menu lateral e área de conteúdo. A única tela é a lista de integrações do tenant; as demais são da E20.

## Consequências

- Os testes (30, 96% de linhas) cobrem o fluxo de login, troca de tenant e logout com um provedor OIDC falso e a API falsa. **O fluxo contra um Keycloak real não foi exercitado nesta máquina** (sem containers); o realm `liberty` do `make local-up` já traz o cliente `liberty-frontend` público, com PKCE S256 e as origens `localhost:5173` e `127.0.0.1:5173`.
- O CSP do nginx hoje só permite `connect-src 'self'`: em produção a origem do provedor OIDC e a da API precisam entrar nessa diretiva (ou a API e a interface ficarem na mesma origem, atrás de um proxy). Isso fica para o empacotamento (E21).
- O bundle tem cerca de 610 kB (184 kB comprimido), dominado pelo Mantine; a divisão por rota pode vir com as telas da E20.
