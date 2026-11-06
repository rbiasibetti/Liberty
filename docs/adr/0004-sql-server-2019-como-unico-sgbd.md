# 0004. SQL Server 2019+ como único SGBD

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D4

## Contexto

O ambiente dos clientes é SQL Server; dialetos diferentes multiplicam código e testes.

## Decisão

Core e tenants usam SQL Server 2019 ou superior. Paginação, tradução de erros e SQL gerado seguem esse dialeto.

## Consequências

`OFFSET ... FETCH`, códigos de erro nativos (2627, 2601, 547). Suporte a outro SGBD exige novo ADR.

## Alternativas descartadas

PostgreSQL (usado nos exemplos da proposta original).
