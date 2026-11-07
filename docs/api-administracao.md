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
| `POST /integrations/{key}/versions/{no}/sandbox` | INTEGRATOR | Executa a versão em HML com a transação sempre desfeita; devolve resultado, SQL e logs |
| `POST /integrations/{key}/versions/{no}/publish/{env}` | PUBLISHER | Publica em `HML` ou `PRD` (`{comment}`) |
| `POST /integrations/{key}/environments/{env}/rollback` | PUBLISHER | Volta à publicação anterior |
| `DELETE /integrations/{key}/environments/{env}/publication` | PUBLISHER | Despublica (204) |
| `GET /environments/{env}/openapi.json` | VIEWER | OpenAPI das integrações publicadas |

Regras: quem escreveu a versão não a rejeita nem a publica (403); PRD exige publicação prévia em HML (409); snapshot inválido ou que falha nas conferências volta em 422 com `errors[]` por campo; publicar vale na hora na instância e em até 5 s nas demais. Erros seguem o formato `problem+json` de [api-execucao.md](api-execucao.md).

## Sandbox

`POST .../versions/{no}/sandbox` com `{"params": {...}, "body": {...}}` executa qualquer versão contra o banco de **HML** do tenant, pelo mesmo motor da produção, e **desfaz tudo** no fim (nenhuma linha fica, nem as de gatilhos). A resposta é sempre 200 quando a versão existe:

```json
{
  "succeeded": true, "status": 201, "rolledBack": true, "millis": 41,
  "body": { "key": { "id": 17 }, "rows": { "tb_pedido": 1, "tb_item": 2 } },
  "sql": [{ "sql": "SET NOCOUNT ON; DECLARE @o TABLE ...", "rows": 1, "millis": 3 }],
  "sqlTruncated": false, "logs": "texto do console.log dos scripts"
}
```

Quando a integração falha (validação, chave duplicada, recusa do script), `succeeded` é `false` e `problem` traz o status, o tipo e os erros por campo que a produção devolveria, junto do rastro de SQL. O SQL aparece com marcadores, sem os valores enviados.
