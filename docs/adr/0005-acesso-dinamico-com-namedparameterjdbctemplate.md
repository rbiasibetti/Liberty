# 0005. Acesso dinâmico com NamedParameterJdbcTemplate

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D5

## Contexto

As tabelas dos tenants são desconhecidas em tempo de compilação.

## Decisão

O motor usa `NamedParameterJdbcTemplate` com um construtor de SQL próprio: identificadores validados contra o catálogo e entre colchetes, valores sempre como parâmetros nomeados.

## Consequências

Nenhum identificador vem da requisição. O construtor tem testes de mutação (PIT) e fuzzing.

## Alternativas descartadas

JPA no banco do tenant (exige entidades fixas); jOOQ (geração de código incompatível com schema dinâmico).
