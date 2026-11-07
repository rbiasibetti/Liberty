# 0025. Motor de escrita e idempotência

- Status: aceito
- Data: 2026-11-06
- Etapa: E12

## Contexto

Integrações de escrita (INSERT, UPSERT, UPDATE) gravam uma árvore de tabelas (mestre e detalhes em até 5 níveis) no banco do cliente. A gravação precisa ser atômica, segura contra injeção, previsível em erro e repetível sem duplicar.

## Decisão

**Fases.** (1) parâmetros e cabeçalho `Idempotency-Key`; (2) corpo: limite de bytes (5 MB no máximo, menor se o snapshot pedir), JSON estrito (chaves duplicadas, texto após o documento, profundidade acima de 32 e números de mais de 50 caracteres são recusados; decimais mantêm a precisão exata) e no máximo 10.000 itens por lista; (3) JSON Schema do corpo (`bodySchema`, obrigatório em escritas); (4) plano contra o catálogo do tenant; (5) materialização: o corpo vira uma árvore de linhas tipadas, todos os defeitos de entrada saem juntos como violações com o ponteiro JSON do campo (400) e nada vai ao banco antes disso; (6) reserva da chave de idempotência; (7) execução numa única transação no banco do tenant, com prazo de 60 s. Qualquer falha desfaz a árvore inteira.

**Plano.** Tabela precisa ser tabela (views são só leitura), colunas existentes e graváveis (identidade, calculadas e `rowversion` nunca recebem valor do corpo), colunas obrigatórias sem padrão têm de estar mapeadas, `keyColumns` cobre a chave primária. Defeitos de cadastro são 500 genérico (detalhe no log); objeto que sumiu do banco é 503 `schema-changed` e recarrega o catálogo. No máximo 50.000 linhas por requisição (413).

**Valores.** Cada campo vem de um ponteiro no item (`source`), de parâmetro ou de constante, passa pelas transformações (E13) e é convertido pelo tipo da coluna; vai sempre como parâmetro tipado, nunca texto de SQL. Ausente é diferente de `null`: em INSERT a coluna ausente fica de fora e vale o padrão do banco, `null` grava NULL; em UPDATE e UPSERT ausente não altera a coluna. Os valores recebidos nunca são repetidos nas mensagens de erro.

**INSERT.** A chave gerada é capturada com `OUTPUT INSERTED.[pk] INTO @tabela` e lida num `SELECT` do mesmo lote: a forma com tabela de variável vale com ou sem gatilhos (o `OUTPUT` simples falha, erro 334, em tabelas com gatilho), então há um só caminho. O tipo da variável vem do catálogo (inteiros, `uniqueidentifier`, texto, `decimal`); outros tipos de chave são recusados no plano. Filhos sem netos entram em lotes de 500 (execuções consecutivas com as mesmas colunas), preservando a ordem; filhos com netos entram um a um para obter a própria chave. A chave estrangeira para o pai é sempre preenchida pelo motor.

**UPSERT.** `UPDATE ... WITH (UPDLOCK, SERIALIZABLE)` pela chave completa e, se nada foi atualizado, `INSERT`. O bloqueio de intervalo faz duas requisições da mesma chave inexistente se enfileirarem em vez de ambas inserirem; não se usa `MERGE`. **UPDATE** exige exatamente uma linha (0 é 404). Mais de uma linha atualizada desfaz tudo (409 `ambiguous-key`).

**Erros do banco** traduzidos pelo número do erro (o texto varia com o idioma do servidor) em respostas genéricas, sem nome de restrição, tabela ou valor: 2627/2601 e 547 são 409 (`conflict`, `constraint-violation`); valor que o banco não aceita (nulo em coluna obrigatória, texto longo demais, estouro numérico, conversão) é 422 `unprocessable`; deadlock (1205) e tempo de espera de bloqueio (1222) são 503 com `Retry-After: 1`; prazo estourado, 504.

**Idempotência.** O cabeçalho `Idempotency-Key` (1 a 200 caracteres ASCII visíveis) é obrigatório se o snapshot declarar `idempotency.required`, e opcional (mas respeitado) nos demais. A chave vale por integração e tenant, por 24 h, junto do SHA-256 canônico dos parâmetros e do corpo. Fluxo: reserva `IN_PROGRESS` → grava → `DONE` com status e resposta. Mesma chave com outro conteúdo é 409 `idempotency-key-reuse`; mesma chave ainda em andamento, 409 `idempotency-in-progress` (`Retry-After`); mesma chave concluída repete a resposta original com `Idempotent-Replayed: true`. Escrita que falha libera a reserva, para a repetição poder tentar de novo. A reserva fica no banco central e a escrita no banco do tenant, então a conclusão não é atômica com ela: se o processo cair depois de confirmar a escrita e antes de marcar `DONE`, a reserva continua `IN_PROGRESS` e **nunca é tomada de assalto** (a chave fica bloqueada até expirar), preferindo recusar a repetição a gravar duas vezes. Uma tarefa horária apaga os registros expirados.

**Resposta.** INSERT: 201 com `key` (chave primária capturada, se declarada) e `rows` (linhas por tabela); UPSERT: 201 (inseriu) ou 200 (atualizou) com `result` e `key`; UPDATE: 200 com `updated` e `key`. `response.successStatus` do snapshot substitui o status de sucesso. Corpo com tipo diferente de `application/json` é 415.

## Consequências

- As funções de transformação (E13) entram pelo ponto de extensão `FieldTransformer`; até lá, campos com transformações são recusados como erro de cadastro.
- A janela de idempotência descrita acima é aceita e documentada; fechá-la exigiria gravar o registro no banco do tenant (invasivo no schema do cliente).
- O `@EnableScheduling` passou a estar ativo na aplicação (a rotação de chaves da E08 e a limpeza de idempotência dependem dele).
