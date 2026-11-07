# 0033. Testes de ponta a ponta no navegador

- Status: aceito
- Data: 2026-11-07
- Etapa: E21

## Contexto

Os testes de unidade da interface usam o jsdom e os de contrato da API usam o MockMvc; nenhum exercita o navegador, o login OIDC com PKCE e a API real juntos. O Keycloak e o Vault de verdade exigem Docker, que não está disponível em toda máquina de desenvolvimento.

## Decisão

- **Playwright** (`@playwright/test`) com o **Chrome instalado** (`channel: 'chrome'`): nenhum navegador é baixado.
- A pilha de teste é o `E2eStackRunner`, uma classe JUnit ligada só com `-Dliberty.e2e=true`: sobe a API real em 18080 sobre um banco central e um banco de cliente descartáveis, com migrações de verdade, um provedor OIDC falso (`E2eOidc`, descoberta, JWKS, autorização e troca de código **com verificação de PKCE S256**) em 18081 e um Vault falso. Usuários: `integrador`, `aprovador`, `admin`. O OIDC falso só aprova sozinho o usuário escolhido em `/__user`.
- `scripts/e2e.sh` sobe a pilha, espera `/__ready`, roda o Playwright (que sobe o Vite apontando para ela) e derruba tudo ao sair.
- Cada execução usa um sufixo novo nas chaves para não colidir com dados de uma execução anterior da mesma pilha.
- O E2E **não roda na esteira de CI** por enquanto: exige SQL Server e Chrome. Roda localmente e antes de liberar.

## Consequências

- O fluxo do integrador e do aprovador (criar, mapear 2 níveis, sandbox, revisão, HML, PRD, rollback) é verificado de ponta a ponta, e já achou dois defeitos reais de login que os testes de unidade não viam.
- O provedor falso não prova compatibilidade com o Keycloak ou o Entra ID: fluxo de código e PKCE sim; particularidades do provedor (claims, renovação silenciosa, logout) não.
- Um cenário completo é lento (cerca de 20 s mais a subida da API, cerca de 1 min). Novos cenários devem ser poucos e de jornada, não de tela.
