# 0010. Versionamento por snapshot imutável

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D10

## Contexto

Rollback consistente exige versionar script, mapeamento e schema juntos.

## Decisão

Cada versão é um documento JSON imutável da integração inteira, com hash SHA-256 e `schemaVersion`. O motor executa sempre um snapshot completo; rollback é publicar de novo uma versão antiga.

## Consequências

Autor não aprova a própria versão. Um rascunho por integração, publicação por ambiente (HML, PRD).

## Alternativas descartadas

Histórico append-only só do script.
