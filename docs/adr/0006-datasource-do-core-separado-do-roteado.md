# 0006. DataSource do core separado do roteado

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D6

## Contexto

Um único DataSource roteado criava dependência circular e acesso acidental ao core.

## Decisão

`coreDataSource` (Hikari fixo) atende JPA, Flyway e o catálogo de tenants. `TenantRoutingDataSource` atende só o motor, não tem destino padrão e lança erro com contexto vazio.

## Consequências

Dois `JdbcTemplate`/`EntityManager` distintos. O roteador estende `AbstractDataSource`, não `AbstractRoutingDataSource`.

## Alternativas descartadas

Um DataSource roteado para tudo, com banco core como padrão.
