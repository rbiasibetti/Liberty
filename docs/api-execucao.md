# API de execução (leitura e escrita)

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

## Escrita

`POST` (INSERT, UPSERT), `PUT` (UPSERT, UPDATE) e `PATCH` (UPDATE), com corpo `application/json` de até 5 MB (o snapshot pode reduzir o limite) e escopo `integration:execute:<chave>`.

- **Atomicidade:** a árvore inteira (por exemplo pedido, itens e lotes) é gravada numa transação; qualquer falha desfaz tudo.
- **Validação:** o corpo é conferido contra o JSON Schema da integração e contra os tipos das colunas; todos os defeitos voltam juntos em `errors[]`, com o ponteiro JSON do campo em `parameter` (por exemplo `/itens/1/qtd`), e nada é gravado.
- **Idempotência:** envie `Idempotency-Key` (1 a 200 caracteres ASCII visíveis; obrigatório nas integrações que o exigem). Repetir a chave com o mesmo conteúdo devolve a resposta original, com `Idempotent-Replayed: true`, sem gravar de novo (por 24 h). Chave igual com conteúdo diferente é 409; chave ainda em andamento é 409 com `Retry-After`. Se a gravação falhar, a chave é liberada.
- **Resposta:** INSERT devolve 201 com `key` (chave primária gerada) e `rows` (linhas por tabela); UPSERT, 201 ou 200 com `result` (`inserted` ou `updated`) e `key`; UPDATE, 200 com `updated` e `key`, ou 404 se a chave não existe. Os campos podem normalizar o valor com funções (`trim`, `upper`, `lower`, `digits`, `substring`, `replace`, `toDate`, `toDecimal`, `default`, `map`); valor que não converte volta como violação do campo. Campos ausentes no corpo não alteram a coluna em UPDATE e UPSERT; `null` grava NULL.

## Remoção por chave (DELETE)

`DELETE /{tenant}/api/{rota}` remove **uma** linha pela chave completa, vinda dos parâmetros da rota ou da query; não há corpo. Sem registro com a chave, 404; se a chave corresponder a mais de um, 409 e nada é removido. A resposta é `{ "deleted": 1, "key": { ... } }`. Não existe remoção em lote, por filtro ou em cascata pela API, e publicar uma integração DELETE exige um comentário de aprovação (ver [administração](api-administracao.md)).

## Scripts

Integrações podem ter scripts JavaScript que ajustam o corpo antes da gravação (`beforeExecute`) e a resposta (`afterExecute`), e recusar a operação com um status 4xx. Para o consumidor, isso aparece como: `script-rejected` (o status e a mensagem escolhidos pela integração; em escrita, nada é gravado), `script-timeout` (504), `script-error` (500) ou `script-unavailable` (503, tente de novo).

## Limites do plano

Cada tenant tem um limite de requisições por segundo (com rajada) e de execuções simultâneas, definidos pelo plano; o padrão é 50 por segundo, rajada de 100 e 20 simultâneas. Passou do limite, a resposta é `429` imediata, antes de tocar o banco do cliente, com `Retry-After` em segundos. O limite é por tenant: um tenant que excede o plano não afeta os demais. Corpo de escrita: até 5 MB e 10.000 itens por lista.

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
| 409 | Registro único ambíguo, chave duplicada, restrição violada, ou conflito de idempotência |
| 413 | Corpo ou número de linhas acima do limite |
| 429 | Limite do plano: taxa (`rate-limited`, com `Retry-After`, `RateLimit-Limit` e `RateLimit-Remaining`), execuções simultâneas (`tenant-busy`) ou scripts demais; tente de novo depois de `Retry-After` |
| 415 | Escrita com corpo que não é `application/json` |
| 422 | Algum valor não é aceito pelo banco do cliente |
| 503 | Banco do cliente indisponível, schema alterado ou conflito de concorrência; tente de novo (`Retry-After` quando houver) |
| 504 | A consulta (30 s) ou o script (2 s) passou do prazo |
