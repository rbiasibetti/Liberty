package br.com.liberty.engine.sandbox;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.lifecycle.LifecycleService;
import br.com.liberty.engine.outbound.OutboundMode;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.read.ReadEngine;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.engine.route.RoutePattern;
import br.com.liberty.engine.script.ScriptHooks;
import br.com.liberty.engine.script.ScriptLogs;
import br.com.liberty.engine.trace.SqlTrace;
import br.com.liberty.engine.write.WriteEngine;
import br.com.liberty.engine.write.WriteRequest;
import br.com.liberty.snapshot.SnapshotService;
import br.com.liberty.snapshot.model.Param;
import br.com.liberty.snapshot.model.Snapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sandbox: executa uma versão (rascunho, em revisão ou publicada) contra o banco de HML do tenant
 * com a transação sempre desfeita, devolvendo o resultado, o rastro de SQL e o console dos scripts.
 * Só HML; PRD nunca. Os scripts só leem e o {@code http.fetch} é simulado (valida tudo, não envia
 * nada), então nada fora da transação desfeita é tocado. Falhas da integração (validação, conflito,
 * script) não são erros do sandbox: voltam no resultado, junto do rastro, para o integrador
 * depurar.
 */
public final class SandboxService {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Resposta de um problema da integração, no formato do motor. */
  public record Problem(
      int status, String type, String detail, List<ProblemException.Violation> violations) {}

  /** Resultado de uma execução de teste. */
  public record Result(
      boolean succeeded,
      int status,
      JsonNode body,
      Problem problem,
      List<SqlTrace.Entry> sql,
      boolean sqlTruncated,
      String logs,
      long millis,
      boolean rolledBack) {}

  private final LifecycleService lifecycle;
  private final ReadEngine reads;
  private final WriteEngine writes;
  private final ScriptHooks scripts;
  private final SnapshotService snapshots;

  public SandboxService(
      LifecycleService lifecycle,
      ReadEngine reads,
      WriteEngine writes,
      ScriptHooks scripts,
      SnapshotService snapshots) {
    this.lifecycle = lifecycle;
    this.reads = reads;
    this.writes = writes;
    this.scripts = scripts;
    this.snapshots = snapshots;
  }

  public Result run(
      String slug, String key, int versionNo, Map<String, JsonNode> params, JsonNode body) {
    var target = lifecycle.target(slug, key, versionNo);
    Snapshot snapshot = snapshots.parse(target.snapshot().getBytes(StandardCharsets.UTF_8)).model();
    // versionId próprio e negativo: o cache de esquemas do corpo nunca reaproveita um rascunho
    // antigo
    long versionId = -1 - ThreadLocalRandom.current().nextLong(Long.MAX_VALUE - 1);
    var integration =
        new PublishedIntegration(
            target.integrationId(),
            key,
            snapshot.integration().method(),
            versionId,
            target.versionNo(),
            snapshot,
            RoutePattern.parse(snapshot.integration().route()));
    TenantScope scope = new TenantScope(new TenantId(slug), EnvironmentKind.HML);

    Map<String, String> path = new LinkedHashMap<>();
    Map<String, List<String>> query = new LinkedHashMap<>();
    Map<String, String> declared = new LinkedHashMap<>();
    for (Param param : snapshot.request().params()) {
      JsonNode value = params == null ? null : params.get(param.name());
      if (value == null || value.isNull()) {
        continue;
      }
      String text = value.isString() ? value.asString() : value.toString();
      declared.put(param.name(), text);
      if ("path".equals(param.in())) {
        path.put(param.name(), text);
      } else {
        query.put(param.name(), List.of(text));
      }
    }

    SqlTrace trace = new SqlTrace();
    long start = System.nanoTime();
    try (ScriptLogs logs = ScriptLogs.capture();
        OutboundMode mode = OutboundMode.simulate()) {
      try {
        int status;
        JsonNode result;
        if ("SELECT".equals(snapshot.operation().type())) {
          var read = reads.execute(scope, integration, path, query, trace);
          scripts.attemptStarted();
          try {
            result =
                scripts.after(scope, integration, declared, null, MAPPER.valueToTree(read.body()));
          } finally {
            scripts.attemptEnded(); // sandbox: nada é publicado
          }
          status = 200;
        } else {
          byte[] bytes =
              body == null
                  ? new byte[0]
                  : MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
          var written =
              writes.sandbox(scope, integration, new WriteRequest(bytes, path, query, null), trace);
          result = written.body();
          status = written.status();
        }
        return new Result(
            true,
            status,
            result,
            null,
            trace.entries(),
            trace.truncated(),
            logs.text(),
            millis(start),
            true);
      } catch (ProblemException e) {
        return new Result(
            false,
            e.status(),
            null,
            new Problem(e.status(), e.type(), e.getMessage(), new ArrayList<>(e.violations())),
            trace.entries(),
            trace.truncated(),
            logs.text(),
            millis(start),
            true);
      }
    }
  }

  private static long millis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
