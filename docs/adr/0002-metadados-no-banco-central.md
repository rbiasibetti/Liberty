# 0002. Metadados no banco central

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D2

## Contexto

Rotas, versões, auditoria e clientes precisam de um lugar único e consultável.

## Decisão

Os metadados ficam no banco central `liberty_core`, em tabelas com prefixo `int_` e coluna `tenant_id` em toda tabela de tenant.

## Consequências

Uma só migração, um só painel de administração, promoção entre ambientes simples. Toda consulta ao core filtra por `tenant_id`.

## Alternativas descartadas

Réplica dos metadados no banco de cada tenant (N migrações, sem visão global).
