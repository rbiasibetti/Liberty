# Auditoria descartando ou perdendo eventos

**Reconhecer:** alertas `LibertyAuditoriaDescartando` (`liberty_audit_dropped_total` subindo), `LibertyAuditoriaFalhandoNaGravacao` (`liberty_audit_failed_total`) ou `LibertyFilaDeAuditoriaCheia`.

**Entender:** a auditoria é gravada em lote por uma fila de 10.000 posições. Descarte quer dizer que a fila encheu porque o banco central não acompanha: a API **não** é afetada, mas os eventos descartados se perdem (ADR 0029). Perda por falha é gravação que deu erro.

**Olhar primeiro**
1. `liberty_audit_batch_duration_seconds` e a saúde do banco central (CPU, bloqueios, disco).
2. A tabela `int_audit_execution` está particionada por mês e as partições dos próximos meses existem? A manutenção roda todo dia às 03:30 UTC. Para rodar na hora: `EXEC dbo.sp_int_audit_maintain @monthsAhead = 3, @retentionMonths = 13;` (a rotina roda com os direitos do dono; a aplicação não tem DDL).
3. Erros de gravação no log (`Falha ao gravar N eventos de auditoria`).

**Correção:** normalizar o banco central; se for pico de tráfego legítimo e duradouro, aumente `liberty.audit.queue-capacity` e `batch-size`. Eventos já perdidos não são recuperáveis.

**Confirmar:** os contadores `dropped` e `failed` param de subir e `liberty_audit_queue_size` volta perto de zero.
