# 0008. Mapeamento como árvore de nós

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D8

## Contexto

A proposta original tinha três modelos sobrepostos (`mapping_level`, `hierarchy_node_id`, `operation_type`).

## Decisão

Uma integração de escrita é uma árvore: cada nó aponta para uma tabela, um caminho de array, a chave primária, a FK para o pai e a lista de campos. INSERT simples tem um nó; master-detail, dois níveis; profundidade máxima 5.

## Consequências

Um só executor recursivo e uma só tela de mapeamento. Caminhos são JSON Pointer relativos ao item do nó.

## Alternativas descartadas

`mapping_level` + `operation_type` MASTER_DETAIL, com código linear por nível.
