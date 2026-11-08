# 0036. Exportações e remoção por chave

- Status: aceito
- Data: 2026-11-07
- Etapa: E24

## Contexto

A fase 2 pede jobs de exportação em armazenamento de objetos com URL pré-assinada, e DELETE só por chave e com aprovação. Critério de saída: o download só é permitido ao tenant dono do arquivo. Não há S3, MinIO nem Docker disponíveis para desenvolver e testar um adaptador de nuvem.

## Decisão

### Exportações

- **Pedido** (`POST /api/v1/tenants/{slug}/exports`, INTEGRATOR): CSV do resultado de uma integração de **leitura publicada** no ambiente pedido, com os parâmetros declarados por ela. XLSX fica recusado (400) até haver um motivo para a dependência.
- **Geração pela fila** `export` da E23: ganha de graça bulkhead por tenant, recuo, reentrega e mortas. O consumidor lê **pelo próprio motor de leitura**, página a página, então valem as mesmas validações, a mesma conexão do tenant e os mesmos limites de página; nada de SQL próprio. É idempotente (pedido concluído ou falho é ignorado).
- **Limites:** 100 mil linhas (teto do deslocamento do motor de leitura), 100 MiB, 3 pedidos simultâneos por tenant (429), arquivo guardado por 24 h e depois apagado por uma rotina periódica.
- **CSV seguro:** UTF-8 com BOM; texto que começa com `= + - @`, tabulação ou CR recebe um apóstrofo (injeção de fórmula, OWASP); números e booleanos ficam como estão.
- **Armazenamento:** interface `ObjectStore` com `Upload` transacional (o objeto só existe após `commit`; falha ou fechamento sem commit descarta o parcial). A implementação é **em disco** (`LocalObjectStore`), com chave `<tenantId>/<pedido>.csv` escolhida pelo motor e validada (nada de `..`). **O adaptador S3-compatível não foi implementado**: não há como testá-lo aqui, e um adaptador sem teste contra um servidor real seria uma promessa vazia. Ele entra atrás da interface, sem mudar serviço, fila nem API.
- **URL pré-assinada:** `/api/v1/exports/download?token=…`, o único endpoint de aplicação sem login. O token é `base64url(v1|tenant|pedido|expiração).base64url(HMAC-SHA256)`, com chave `LIBERTY_EXPORT_SIGNING_KEY` (32+ caracteres; sem ela, cada instância gera uma e os links não valem entre instâncias nem após reiniciar). Vale 10 minutos, pode ser usado mais de uma vez nesse prazo, e a resposta é `Cache-Control: no-store`, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`.
- **Só o tenant dono baixa (o critério), em três camadas:** (1) o tenant e o pedido estão dentro da parte assinada; adulterar qualquer byte invalida; (2) mesmo com assinatura válida, o pedido é buscado **por tenant e id**, então uma assinatura válida de outro tenant dá 404; (3) pedir o link por outro tenant, ou por um usuário que não é membro, é 404 ou 403. VIEWER não vê nem exporta, porque exportar entrega dados do banco do cliente.

### Remoção por chave (`DELETE`)

- Nova operação `DELETE` (método HTTP `DELETE`): remove **uma linha** pela chave completa (`keyColumns` cobrindo a chave primária, conferida na publicação), com todos os campos vindos de parâmetros da rota ou da query. **Sem corpo, sem filhos, sem filtro, sem lote.**
- Zero linhas: 404. Mais de uma: 409 e a transação desfaz (a chave não era única).
- O sandbox executa e desfaz, mostrando o SQL.
- **Aprovação reforçada:** além dos quatro olhos e do HML antes de PRD que valem para toda integração, **publicar uma versão DELETE exige um comentário** com o motivo (422 `destructive_requires_comment`), que fica registrado como o comentário de revisão da versão. `beforeExecute` não se aplica (não há corpo); `afterExecute` continua valendo e roda antes do commit.
- Sem idempotência por cabeçalho: remover duas vezes dá 404 na segunda, sem efeito colateral.
- Migração V6 amplia a restrição de métodos da tabela de integrações.

## Consequências

- Exportações dependem da fila: com `liberty.queue.enabled=false` os pedidos ficam `PENDING`.
- A paginação por deslocamento não é consistente se os dados mudam durante a exportação, e sem ordenação estável a ordem pode variar; a integração deve definir ordenação. Para volumes acima do teto, o caminho é a exportação por faixa de chave, não aumentar o limite.
- O armazenamento em disco exige volume compartilhado se houver mais de uma instância (o consumidor que gera e a instância que serve o download podem ser diferentes). Em implantação com várias instâncias, o adaptador de objetos passa a ser pré-requisito.
- Não há tela para exportações; só API. A tela de integrações já aceita criar e mapear DELETE.
