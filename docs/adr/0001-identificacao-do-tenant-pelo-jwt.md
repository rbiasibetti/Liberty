# 0001. Identificação do tenant pelo JWT

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D1

## Contexto

Toda requisição precisa identificar o tenant sem que o cliente possa escolher o próprio.

## Decisão

O tenant vem exclusivamente da claim `tenant_id` de um JWT assinado (RS256). Sem JWT válido a resposta é 401; não existe tenant padrão nem fallback para o banco central.

## Consequências

O contexto é preenchido por um filtro depois da validação do token. Um teste automatizado tenta acesso cruzado em cada endpoint e espera 404.

## Alternativas descartadas

Header `X-Tenant-ID` enviado pelo cliente, com fallback para o banco core (permite acessar qualquer tenant).
