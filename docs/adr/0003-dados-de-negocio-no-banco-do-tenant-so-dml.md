# 0003. Dados de negócio no banco do tenant, só DML

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D3

## Contexto

O schema do cliente já existe e pertence a ele.

## Decisão

A plataforma lê e grava no SQL Server de cada tenant já existente, com usuário de privilégio mínimo (SELECT, INSERT, UPDATE nas tabelas liberadas). Não cria bancos nem tabelas no tenant.

## Consequências

Nenhum DDL no tenant; a plataforma não faz onboarding de banco, só cadastra a conexão. Falhas de permissão aparecem na publicação.

## Alternativas descartadas

Criar tabelas `int_` e bancos nos tenants (a plataforma passaria a ser dona do schema do cliente).
