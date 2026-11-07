# API de execução (leitura)

Contrato para os sistemas consumidores. Decisões e motivos: [ADR 0024](adr/0024-motor-de-leitura.md).

## Autenticação

1. `POST /oauth2/token` com `grant_type=client_credentials` e `Authorization: Basic base64(client_id:client_secret)`; o token dura 15 minutos.
2. Chame `GET|POST|PUT|PATCH /{tenant}/api/{rota-da-integração}` com `Authorization: Bearer <token>`. O `{tenant}` precisa ser o do token; o ambiente (HML ou PRD) também vem do token.
3. O token precisa ter o escopo `integration:execute:<chave-da-integração>`.

## Leitura

Parâmetros: os declarados pela integração, mais `limit` (página), `offset` e `sort`.

```http
GET /acme/api/pedidos?cliente=2&periodo=2026-01-05,2026-01-07&sort=-vl_total&limit=20&offset=0
```

```json
{
  "items": [{ "id": 25, "nr_pedido": "P-0025", "vl_total": 250.50, "dt_pedido": "2026-01-25T10:00:00", "ativo": true, "nota": null }],
  "page": { "limit": 20, "offset": 0, "returned": 1, "hasMore": false }
}
```

- `sort`: uma coluna ordenável, `-` na frente para decrescente. A paginação é estável (desempate pela chave primária).
- Filtros: `IN` e `BETWEEN` recebem valores separados por vírgula (até 100 e exatamente 2). Datas sem hora valem 00:00; como limite superior valem o fim do dia.
- Integrações de registro único devolvem o objeto, ou 404 se não existir e 409 se mais de um corresponder.
- Valores: inteiros como número, decimais exatos, datas e horas ISO 8601, binário em base64, `null` para nulo.

## Erros

Sempre `application/problem+json`, com o mesmo identificador no corpo (`requestId`) e no cabeçalho `X-Request-Id` (você pode enviar o seu: 8 a 64 caracteres entre letras, números, `.`, `_` e `-`).

```json
{
  "type": "urn:liberty:problem:validation",
  "title": "Requisição inválida",
  "status": 400,
  "detail": "Um ou mais parâmetros são inválidos",
  "requestId": "6f1c...",
  "errors": [{ "parameter": "limit", "code": "out_of_range", "message": "Valor fora do intervalo 1 a 20" }]
}
```

| Status | Quando |
| --- | --- |
| 400 | Parâmetro desconhecido, repetido, vazio, do tipo errado ou fora dos limites |
| 401 | Token ausente, inválido, expirado ou cliente revogado |
| 403 | Token sem o escopo da integração, ou chamada de usuário em rota de sistema |
| 404 | Rota não publicada neste ambiente, tenant diferente do token, ou registro inexistente |
| 405 | A rota existe para outro método; o cabeçalho `Allow` lista os aceitos |
| 409 | Registro único com mais de uma correspondência |
| 501 | Operação de escrita ainda não disponível (E12) |
| 503 | Banco do cliente indisponível ou schema alterado; tente de novo |
| 504 | A consulta passou do prazo de 30 s |
