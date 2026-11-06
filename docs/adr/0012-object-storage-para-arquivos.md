# 0012. Object storage para arquivos

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D12

## Contexto

Exportações não podem depender de disco local de uma instância.

## Decisão

Arquivos gerados vão para armazenamento compatível com S3 e são entregues por URL pré-assinada de curta duração, sempre após checar o tenant dono.

## Consequências

Entra na fase 2 (E24). O armazenamento local de desenvolvimento será escolhido na E24: as imagens da edição comunitária do MinIO deixaram de ser distribuídas, então ele não está no ambiente local da E03.

## Alternativas descartadas

`/tmp/exports` na instância.
