# API de administração (ciclo de vida)

Para usuários autenticados pelo provedor OIDC (token de usuário, nunca o de sistema). Base: `/api/v1/tenants/{slug}`. Decisões: [ADR 0027](adr/0027-ciclo-de-vida-das-integracoes.md).

| Método e caminho | Papel mínimo | O que faz |
| --- | --- | --- |
| `GET /integrations` | VIEWER | Lista as integrações |
| `POST /integrations` | INTEGRATOR | Cria `{key, name, method, route}` (201; 409 se já existe) |
| `GET /integrations/{key}/versions` | VIEWER | Versões, estado, autor e revisor |
| `GET /integrations/{key}/versions/{no}/snapshot` | VIEWER | Snapshot da versão |
| `PUT /integrations/{key}/draft` | INTEGRATOR | Salva o snapshot como rascunho (cria ou substitui) |
| `POST /integrations/{key}/versions/{no}/submit` | INTEGRATOR | Envia o rascunho para revisão |
| `POST /integrations/{key}/versions/{no}/reject` | PUBLISHER | Rejeita (`{comment}`) |
| `POST /integrations/{key}/versions/{no}/publish/{env}` | PUBLISHER | Publica em `HML` ou `PRD` (`{comment}`) |
| `POST /integrations/{key}/environments/{env}/rollback` | PUBLISHER | Volta à publicação anterior |
| `DELETE /integrations/{key}/environments/{env}/publication` | PUBLISHER | Despublica (204) |
| `GET /environments/{env}/openapi.json` | VIEWER | OpenAPI das integrações publicadas |

Regras: quem escreveu a versão não a rejeita nem a publica (403); PRD exige publicação prévia em HML (409); snapshot inválido ou que falha nas conferências volta em 422 com `errors[]` por campo; publicar vale na hora na instância e em até 5 s nas demais. Erros seguem o formato `problem+json` de [api-execucao.md](api-execucao.md).
