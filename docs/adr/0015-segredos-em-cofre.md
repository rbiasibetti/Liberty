# 0015. Segredos em cofre

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D15

## Contexto

Senhas dos bancos dos tenants não podem vazar junto com o core.

## Decisão

As senhas dos bancos dos tenants ficam em cofre (Azure Key Vault ou HashiCorp Vault); o core guarda só a referência. Nenhum segredo em `application.yml`, no repositório ou em log.

## Consequências

Desenvolvimento local usa Vault em modo dev ou variáveis de ambiente fora do Git (`.env.local`, ignorado). Varredura de segredos roda no CI (E02).

## Alternativas descartadas

Senha em arquivo de configuração ou em coluna do core.
