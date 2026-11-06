# 0012. Object storage para arquivos

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D12

## Contexto

Exportações não podem depender de disco local de uma instância.

## Decisão

Arquivos gerados vão para armazenamento compatível com S3 e são entregues por URL pré-assinada de curta duração, sempre após checar o tenant dono.

## Consequências

Entra na fase 2 (E24). O desenvolvimento local usa MinIO.

## Alternativas descartadas

`/tmp/exports` na instância.
