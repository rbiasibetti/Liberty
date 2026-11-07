# Backup, restauração e perda do banco central

O `liberty_core` guarda todos os metadados: integrações, versões, publicações, conexões (sem segredos, que ficam no Vault), clientes de API, papéis, idempotência e auditoria. Os bancos dos clientes **não** são cobertos aqui: são do cliente.

## Política

| Item | Valor |
|---|---|
| Backup completo | diário, `WITH CHECKSUM` |
| Backup de log | a cada 15 min (modelo de recuperação `FULL` em produção) → RPO ≤ 15 min |
| Retenção | 35 dias, cópia fora do servidor |
| Verificação | `RESTORE VERIFYONLY` após cada backup; restauração completa em banco paralelo todo mês |
| RTO alvo | 1 h |

Em edições com suporte, acrescente `COMPRESSION` ao backup. A Express não suporta.

## Backup

```sql
BACKUP DATABASE [liberty_core]
  TO DISK = N'/var/opt/mssql/backup/liberty_core.bak'
  WITH CHECKSUM, INIT, FORMAT;
RESTORE VERIFYONLY FROM DISK = N'/var/opt/mssql/backup/liberty_core.bak' WITH CHECKSUM;
```

O caminho precisa ser gravável pelo serviço do SQL Server (`SERVERPROPERTY('InstanceDefaultBackupPath')` mostra o padrão).

## Restauração em outro nome (ensaio ou investigação)

Descubra os arquivos lógicos e restaure com `MOVE`:

```sql
RESTORE FILELISTONLY FROM DISK = N'/var/opt/mssql/backup/liberty_core.bak';

RESTORE DATABASE [liberty_core_rst]
  FROM DISK = N'/var/opt/mssql/backup/liberty_core.bak'
  WITH CHECKSUM, RECOVERY,
       MOVE N'<lógico-dados>' TO N'/var/opt/mssql/data/liberty_core_rst.mdf',
       MOVE N'<lógico-log>'   TO N'/var/opt/mssql/data/liberty_core_rst_log.ldf';
```

Confira: `int_flyway_schema_history` com as mesmas migrações do original, contagem de `int_tenant`, `int_integration` e `int_publication`, e os gatilhos de imutabilidade (`sys.triggers`). O teste `BackupRestoreTest` automatiza essa conferência.

## Perda total do banco

1. Pare o tráfego (tire a API do balanceador) para não aceitar publicações com estado incompleto.
2. Restaure o último backup completo `WITH NORECOVERY`, depois os de log em ordem, o último com `RECOVERY` (ou `STOPAT` para voltar a um instante).
3. Recrie o login/usuário `liberty_core_app` se o servidor for novo e reaplique as permissões (`deploy/local/sql/10-core-users.sql` é o modelo). Se o login de servidor for novo, o usuário restaurado fica órfão: religue com `ALTER USER liberty_core_app WITH LOGIN = liberty_core_app`.
4. Rode a esteira de migração (`liberty-core-migrations`): como o backup traz `int_flyway_schema_history`, só migrações posteriores ao backup serão aplicadas. Suba a API (a subida não migra) e confira `GET /actuator/health/readiness`.
5. Se o backup for anterior a uma versão que já está em produção, as rotas voltam ao estado do backup: avise os integradores, confira o estado de cada integração na lista e republique o que faltar. Chaves de idempotência perdidas fazem repetições de clientes serem executadas de novo: avise os clientes que usam `Idempotency-Key` na janela perdida.
6. Registre o incidente: horário da perda, backup usado, janela de dados perdida.

## Perda parcial (tabela apagada por engano)

Restaure em outro nome (acima), copie as linhas necessárias com `INSERT ... SELECT` respeitando a ordem das chaves estrangeiras e **nunca** altere versões já criadas: elas são imutáveis por gatilho, e o gatilho existe por isso.

## Segredos

Credenciais dos bancos dos clientes estão no Vault, não no `liberty_core`. Restaurar o core não as devolve nem as expõe. Se o Vault também foi perdido, use o runbook [tenant-sem-conexao.md](tenant-sem-conexao.md) para recadastrar cada conexão.

## Auditoria

A auditoria é particionada por mês; backups completos a incluem. Tabelas `int_audit_archive_*` (criadas pela manutenção) também entram no backup; guarde-as pela retenção legal definida pelo cliente.
