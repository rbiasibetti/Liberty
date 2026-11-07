# 0022. Catálogo do tenant e identificadores SQL

- Status: aceito
- Data: 2026-11-06
- Etapa: E09

## Contexto

O motor monta SQL sobre tabelas e colunas que só existem em tempo de execução, no banco do cliente (ADR 0005). Valores sempre viram parâmetros, mas nomes de tabela e coluna não podem ser parâmetros JDBC: são o ponto onde uma injeção de SQL entraria.

## Decisão

- **Sintaxe antes do banco.** `TableRef` só aceita `tabela` (esquema `dbo`) ou `esquema.tabela`, com identificadores simples `[A-Za-z_][A-Za-z0-9_]{0,127}`. Colchetes, aspas, espaços, hífens, pontos extras, curingas e Unicode são recusados sem consultar o banco. Tabelas com nomes fora do padrão existem no cliente mas não são utilizáveis (e não aparecem nas listas do editor).
- **Existência no catálogo.** O nome só serve para localizar o objeto real em `DatabaseMetaData`, com o usuário de privilégio mínimo do tenant: o que ele não enxerga não existe para a plataforma. Os padrões do JDBC são LIKE, então `_`, `%`, `[` e `]` são escapados e o resultado é conferido por igualdade; a grafia exata vence e só uma correspondência única por diferença de maiúsculas é aceita.
- **Texto de SQL só do catálogo.** `SqlIdentifiers` devolve `[esquema].[tabela]` e `[coluna]` com a grafia que o catálogo reportou, nunca com a do usuário, e revalida o padrão simples como segunda barreira. Colunas desconhecidas e tabelas inexistentes viram `UnknownDatabaseObjectException` sem ecoar a entrada; mensagens só citam nomes simples.
- **Esquemas do sistema nunca** (`sys`, `INFORMATION_SCHEMA`, `guest`, `db_*`). Views são somente leitura.
- **Metadados úteis ao motor:** tipo (família), tamanho, escala, nulabilidade, valor padrão, chave primária ordenada e coluna gerada (identidade, calculada, rowversion), que o motor nunca grava.
- **Cache por tenant.** Acertos por 5 minutos, "não existe" por 30 segundos (nome inventado não martelar o banco do cliente). Erro 207 (coluna inválida) ou 208 (objeto inválido) numa execução chama `onDatabaseError`, que descarta o cache do tenant.

## Consequências

- O motor (E11, E12) obtém tabelas por `TenantCatalog.table(scope, referência)` e monta SQL só com `SqlIdentifiers`; qualquer concatenação de nome vindo de outro lugar é defeito.
- O usuário de banco do tenant precisa de `VIEW DEFINITION` ou de alguma permissão sobre a tabela para enxergá-la.
- Um schema alterado no cliente aparece em até 5 minutos, ou na hora, se uma execução falhar com 207 ou 208.
- Identificadores com espaço ou hífen exigem renomear ou criar uma view no banco do cliente.

## Alternativas descartadas

- Escapar nomes com `QUOTENAME` e aceitar qualquer string: confia no escape em vez de recusar o inválido; a lista branca é mais simples de auditar.
- Validar só por expressão regular sem consultar o catálogo: aceitaria tabelas inexistentes e esquemas do sistema.
- Carregar o catálogo inteiro do tenant: custoso em bancos com milhares de tabelas; a leitura é por tabela, sob demanda.
