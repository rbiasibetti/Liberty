# 0029. Auditoria e observabilidade

- Status: aceito
- Data: 2026-11-07
- Etapa: E17

## Contexto

Cada execução precisa deixar um registro auditável, e a operação precisa de métricas, rastros e alertas, sem que nada disso atrase a API nem exponha dados pessoais.

## Decisão

**Auditoria assíncrona.** A requisição entrega um `AuditEvent` a uma fila limitada (10.000) sem esperar: se estiver cheia, o evento é **descartado e contado** (`liberty.audit.dropped`), nunca bloqueia nem falha a resposta. Uma thread própria grava em lote (até 500 ou a cada 200 ms) com `INSERT` em lote JDBC no banco central. Falha de gravação perde o lote e é contada (`liberty.audit.failed`); eventos de tenant desconhecido também. No encerramento a fila é drenada com prazo de 10 s. O identificador numérico do tenant é resolvido na thread de gravação (cache de 5 min), não na requisição.

**O que se grava.** Só metadados: tenant, integração, versão, ambiente, método, rota (o padrão, não o caminho recebido), status, duração total, tempo no banco do cliente e em scripts, código do erro (o `type` do problema), mensagem de erro, `requestId` e `client_id`. Corpo, parâmetros e valores **nunca** entram. A mensagem de erro passa por máscara (e-mails, sequências longas de dígitos, cabeçalhos de autorização e tokens longos) e é limitada a 300 caracteres. `int_audit_payload` (amostragem do payload mascarado) continua só por JDBC e não é gravada ainda.

**Partições mensais e arquivamento (V3).** `int_audit_execution` é particionada por mês (`created_at`) com índices alinhados, 14 meses para trás e 24 para frente. A rotina `sp_int_audit_maintain` (com `EXECUTE AS OWNER`, então a aplicação não ganha DDL) é chamada diariamente por um job: cria as partições dos próximos meses, **arquiva por troca de partição** (`SWITCH`, só metadados) as anteriores à retenção (13 meses) em tabelas `int_audit_archive_AAAAMMDD`, apaga o payload correspondente e funde a partição vazia. Um bloqueio de aplicação serializa instâncias concorrentes. As tabelas de arquivo ficam no banco até a operação exportá-las ou apagá-las. A chave estrangeira do payload foi removida (impedia a troca de partições; o V1 já dizia que a auditoria não tem FK).

**Métricas (Micrometer/Prometheus).** `liberty.integration.duration` (histograma, por tenant, integração e classe de status), `liberty.integration.db.duration`, `liberty.script.duration`, `liberty.script.workers`, e as da auditoria (`written`, `dropped`, `failed`, `queue.size`, `queue.capacity`, `batch.duration`). Todas com a etiqueta `application`. `/actuator/prometheus` exige HTTP Basic de uma conta técnica (`metrics`, senha em `LIBERTY_METRICS_PASSWORD`); sem senha configurada o endpoint fica fechado para todos, porque as etiquetas revelam tenants e integrações.

**Rastros (OpenTelemetry).** `spring-boot-starter-opentelemetry` com exportação OTLP/HTTP; amostragem **desligada por padrão** (`LIBERTY_TRACE_SAMPLING=0.0`), ligada por ambiente junto de `LIBERTY_OTLP_TRACES_ENDPOINT`.

**Dashboards e alertas.** `deploy/local`: scrape autenticado no Prometheus, regras de alerta (instância fora do ar, 5xx acima de 2%, p95 acima de 300 ms, auditoria descartando ou perdendo eventos, fila acima de 80%, scripts lentos) e o dashboard "Liberty - operação" no Grafana, provisionados pelo `make local-up`. Os limiares são pontos de partida a calibrar com a carga real.

## Consequências

- Com o banco central lento, a latência da API não muda; o custo é perder eventos de auditoria, o que fica visível na métrica e no alerta. O teste automático entrega 200.000 eventos a um banco travado sem esperar (todos os produtores voltam em segundos) e confere que cada descarte foi contado.
- A auditoria é "melhor esforço": não serve de livro-razão contábil. Se isso for exigido, a fila deve ser trocada por gravação síncrona ou por um log durável.
- Os manifestos do Kubernetes ainda não ligam variáveis de ambiente (nem a senha de métricas); isso entra com o empacotamento de produção.
- O rastro de uma execução não registra o SQL (isso é do sandbox, ADR 0028).
