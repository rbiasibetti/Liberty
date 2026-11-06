# 0011. Redis para estado compartilhado

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D11

## Contexto

A aplicação roda em duas ou mais instâncias.

## Decisão

Rate limit, cache de metadados com invalidação e lista de revogação de clientes ficam no Redis.

## Consequências

Redis passa a ser dependência de disponibilidade; na falta, o rate limit falha fechado por tenant e os caches caem para o core.

## Alternativas descartadas

Mapas em memória e semáforos locais.
