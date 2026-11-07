# 0021. Autenticação e autorização

- Status: aceito
- Data: 2026-11-06
- Etapa: E08

## Contexto

Há dois tipos de chamador: sistemas consumidores (executam integrações) e pessoas (configuram e publicam). O tenant nunca pode ser escolhido pelo chamador (ADR 0001), e a proposta original aceitava um header `X-Tenant-ID` e um fallback para o core.

## Decisão

**Sistemas consumidores.** Servidor de autorização embutido (Spring Authorization Server), só com `client_credentials` e autenticação `client_secret_basic`. Clientes e hashes (`{bcrypt}`) vêm de `int_api_client`. O token é um JWT RS256 de 15 minutos com `iss`, `aud=liberty-api`, `tenant_id`, `env`, `client_id` e `scope`. Nada é guardado por token. Só `/oauth2/token`, `/oauth2/jwks` e o metadado de descoberta existem; autorização, introspecção, revogação e registro não são expostos.

**Chaves de assinatura.** Ficam no Vault (KV v2, um campo por chave), compartilhadas entre instâncias. Cada instância recarrega a cada 5 minutos e tenta rotacionar uma vez por dia; a gravação condicional (check-and-set) garante uma única chave nova. Rotação a cada 90 dias; a chave anterior fica 24 horas só para verificação. O JWKS publica apenas partes públicas. `liberty.security.keys.store=MEMORY` existe só para desenvolvimento e testes, e a aplicação avisa no log.

**Revogação.** O status do cliente no core (REVOGADO, expirado, tenant suspenso) é conferido a cada requisição por um cache de 15 segundos, e a emissão sempre lê o banco. Isso substitui a lista de revogação no Redis do ADR 0011: uma fonte de verdade só, sem dependência extra de disponibilidade, e o atraso máximo é conhecido (15 s). O Redis continua para o rate limit (E18).

**Usuários.** Tokens do provedor OIDC validados por `iss`, assinatura (JWKS do provedor), expiração e `azp` igual ao cliente da interface. Papéis por tenant vêm de `int_user_role`, lidos a cada decisão (revogar vale na hora); o administrador da plataforma vem de um papel do provedor. O papel `ROLE_CLIENT` só é concedido a tokens do emissor local: um token do OIDC com claim `client_id` nunca vira cliente.

**Rotas.** `/{slug}/api/**` exige `ROLE_CLIENT` e o tenant vem do token; slug diferente do tenant do token responde 404, sem revelar que o tenant existe. `/api/v1/**` exige autenticação e `@PreAuthorize` em cada endpoint (hierarquia PUBLISHER ⊇ INTEGRATOR ⊇ VIEWER; não-membro e tenant inexistente respondem igual, 403). O resto é negado. Slugs de tenant reservados para rotas da plataforma são recusados no domínio e por CHECK no banco (V2).

**Postura HTTP.** Sem sessão nem cookies, sem CSRF (só Bearer), CORS com lista explícita de origens e sem credenciais, CSP `default-src 'none'`, HSTS, `no-referrer`, `nosniff`, `X-Frame-Options: DENY`, respostas 401 e 403 em problem+json.

## Consequências

- Um teste percorre todos os mapeamentos de controller e falha se algum não declarar `@PreAuthorize`; endpoint novo precisa entrar na matriz de acesso (`SecurityAccessTest`).
- Contra força bruta no endpoint de token vale o rate limit da E18; até lá, segredos de cliente devem ser longos e aleatórios (a administração da E20 os gera).
- Mudar o tamanho do cache de clientes muda o atraso máximo da revogação.
- A aplicação não sobe sem `LIBERTY_SECURITY_ISSUER`, `LIBERTY_OIDC_ISSUER` e `LIBERTY_VAULT_URI`, e sem alcançar o Vault (a menos que as chaves sejam em memória).

## Alternativas descartadas

- Servidor de autorização externo para clientes (Keycloak também para máquina): mais um ponto de falha e o claim de tenant exigiria mapeadores customizados.
- Chaves geradas por instância: tokens de uma instância não valeriam em outra.
- Lista de revogação no Redis: segunda fonte de verdade sem ganho sobre o cache de 15 segundos.
- Papéis de tenant no token do OIDC: revogar um papel só valeria quando o token expirasse.
