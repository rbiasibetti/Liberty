# 0024. Motor de leitura e roteamento

- Status: aceito
- Data: 2026-11-06
- Etapa: E11

## Contexto

Integrações de leitura (SELECT) são publicadas por tenant e ambiente e executadas por sistemas consumidores. O motor precisa resolver a rota, validar a entrada, montar SQL seguro sobre tabelas desconhecidas em tempo de compilação e responder de forma estável e previsível.

## Decisão

**Roteamento.** Um controlador atende `/{tenant}/api/**`; tenant e ambiente vêm do token (ADR 0021). A rota é resolvida entre as publicações ativas do tenant e ambiente (`int_publication`), com literal vencendo variável da esquerda para a direita. Duas integrações com a mesma estrutura de rota e método são ambíguas: serve a mais antiga e as outras geram erro no log (a publicação, na E15, deve impedir isso). Caminho inexistente responde 404; método errado, 405 com `Allow`; token sem o escopo `integration:execute:<chave>`, 403; escrita segue o ADR 0025. Caminhos com `.`, `..`, `/` ou controle decodificados viram 404, e o firewall do Spring recusa formas codificadas perigosas com 400.

**Atualização das rotas.** Cada instância guarda a tabela de rotas e, a cada 5 s, confere uma marca barata das publicações ativas (contagem, maior id e soma de ids); só relê e reanalisa os snapshots se a marca mudou. Publicar, despublicar ou reverter em qualquer instância vale nas outras em até 5 s, sem canal extra (isso substitui o pub/sub no Redis previsto no ADR 0011; o Redis fica para o rate limit). A instância que publica chama `invalidate` e vê na hora. Se a releitura falhar, a tabela anterior continua servindo. Snapshots inválidos ou que não conferem com o cadastro (chave, método, rota) não são servidos.

**Contrato de leitura.**
- Parâmetros: só os declarados no snapshot mais `limit`, `offset` e `sort` (nomes reservados); desconhecido, repetido, vazio, maior que o limite ou do tipo errado é 400, com uma violação por parâmetro e sem repetir o valor recebido.
- Página: `limit` até o máximo do snapshot (no máximo 1.000), `offset` até 100.000; a resposta traz `items` e `page` (`limit`, `offset`, `returned`, `hasMore`, descoberto com uma linha extra). `single` devolve o objeto, 404 se não houver, 409 se houver mais de um.
- Ordenação: uma coluna entre as `sortable` do snapshot (prefixo `-` para decrescente); a chave primária (ou, sem ela, todas as colunas retornadas) entra como desempate para a paginação não repetir nem pular linhas.
- Filtros: `=`, `<>`, `>`, `>=`, `<`, `<=`, `LIKE_PREFIX` (curingas escapados, valor sempre literal), `IN` (até 100 itens) e `BETWEEN` (dois valores). O valor é convertido pelo tipo da coluna no catálogo e vai como parâmetro tipado. Em colunas de data e hora, uma data sem hora vale 00:00 e, como limite superior (`<=`, `>`, segundo valor do `BETWEEN`), o fim do dia.
- Valores no JSON: inteiros como números (64 bits), decimais exatos, datas e horas em ISO 8601 (data e hora sem fuso quando a coluna não tem fuso), binário em base64, UUID e texto como string, nulo como `null`.

**Execução.** Transação somente leitura no banco do tenant, com prazo de 30 s; o SQL só tem identificadores do catálogo e marcadores `:pN` (ADR 0022).

**Erros.** `application/problem+json` (RFC 9457) com `type` `urn:liberty:problem:*`, `title`, `detail`, `requestId`, `instance` igual a `urn:liberty:request:<requestId>` (nunca o caminho recebido) e `errors[]` quando há violações por parâmetro. Banco do tenant indisponível é 503; consulta que passa do prazo, 504; erro 207 ou 208 (schema do cliente mudou) é 503 `schema-changed` e recarrega o catálogo; qualquer outra falha é 500 genérico, com o detalhe só no log. Toda resposta, inclusive 401 e 403, leva `X-Request-Id`, aceito do chamador só se for simples (8 a 64 caracteres seguros), senão gerado.

## Consequências

- O motor depende do catálogo (E09), do snapshot (E10) e dos pools por tenant (E07); a escrita (E12) reutiliza roteamento, erros e identificação.
- Mudar o intervalo de 5 s muda o atraso máximo de uma publicação nas outras instâncias.
- Colunas binárias, XML e outras sem suporte a filtro fazem a integração falhar com 500 se forem usadas num filtro; a publicação (E15) deve recusá-las antes.

## Alternativas descartadas

- Pub/sub no Redis para invalidar rotas: segunda fonte de verdade sem ganho sobre a conferência periódica da marca.
- `offset` ilimitado: paginação funda é cara e vira vetor de lentidão.
- Aceitar `sort` livre com validação por catálogo: o cliente escolheria colunas sem índice; a lista do snapshot é decidida por quem conhece o banco.
