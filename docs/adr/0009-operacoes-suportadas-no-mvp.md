# 0009. Operações suportadas no MVP

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D9

## Contexto

Escopo precisa ser fechado para o MVP.

## Decisão

SELECT, INSERT, UPSERT (UPDATE seguido de INSERT sob lock) e UPDATE por chave completa. DELETE fica para a fase 2, só por chave e com aprovação.

## Consequências

Atualização sem chave é recusada na validação da versão. UPSERT não usa `MERGE`.

## Alternativas descartadas

DELETE livre e UPDATE sem chave.
