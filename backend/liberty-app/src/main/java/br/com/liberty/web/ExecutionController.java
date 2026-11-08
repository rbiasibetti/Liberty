package br.com.liberty.web;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.audit.AuditEvent;
import br.com.liberty.engine.audit.AuditRecorder;
import br.com.liberty.engine.audit.RequestTimings;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.read.ReadEngine;
import br.com.liberty.engine.read.ReadResult;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.engine.route.RouteMatch;
import br.com.liberty.engine.route.RouteTable;
import br.com.liberty.engine.script.ScriptHooks;
import br.com.liberty.engine.traffic.TrafficGuard;
import br.com.liberty.engine.write.WriteEngine;
import br.com.liberty.engine.write.WriteRequest;
import br.com.liberty.engine.write.WriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * Porta de entrada das integrações publicadas: {@code /{tenant}/api/...}. O tenant e o ambiente vêm
 * do token (o filtro de segurança já os pôs no contexto e recusou slug alheio); aqui a rota é
 * resolvida entre as publicações ativas do tenant, o escopo {@code integration:execute:<chave>} é
 * exigido e a operação é executada.
 */
@RestController
class ExecutionController {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private static final String SCOPE_PREFIX = "SCOPE_integration:execute:";

  private final PublishedRoutes routes;
  private final ReadEngine reads;
  private final WriteEngine writes;
  private final ScriptHooks scripts;
  private final AuditRecorder audit;
  private final TrafficGuard guard;
  private final MeterRegistry meters;

  ExecutionController(
      PublishedRoutes routes,
      ReadEngine reads,
      WriteEngine writes,
      ScriptHooks scripts,
      AuditRecorder audit,
      TrafficGuard guard,
      MeterRegistry meters) {
    this.guard = guard;
    this.scripts = scripts;
    this.audit = audit;
    this.meters = meters;
    this.routes = routes;
    this.reads = reads;
    this.writes = writes;
  }

  /** O que a auditoria e as métricas precisam saber da execução, preenchido ao longo dela. */
  private static final class Seen {
    PublishedIntegration integration;
    int status = 500;
    String errorType;
    String errorDetail;
  }

  @RequestMapping("/{slug}/api/**")
  @PreAuthorize("hasRole('CLIENT')")
  ResponseEntity<Object> execute(HttpServletRequest request, Authentication authentication) {
    TenantScope scope = TenantContext.require();
    Seen seen = new Seen();
    long start = System.nanoTime();
    RequestTimings.begin();
    try (TrafficGuard.Permit permit = guard.admit(scope)) {
      ResponseEntity<Object> response = handle(request, authentication, scope, seen);
      seen.status = response.getStatusCode().value();
      return response;
    } catch (ProblemException e) {
      seen.status = e.status();
      seen.errorType = e.type().substring(e.type().lastIndexOf(':') + 1);
      seen.errorDetail = e.getMessage();
      throw e;
    } finally {
      observe(
          request, authentication, scope, seen, System.nanoTime() - start, RequestTimings.end());
    }
  }

  private void observe(
      HttpServletRequest request,
      Authentication authentication,
      TenantScope scope,
      Seen seen,
      long nanos,
      RequestTimings.Totals totals) {
    if (seen.integration == null) {
      return; // rota inexistente ou método errado: nada a atribuir a uma integração
    }
    String integration = seen.integration.key();
    String tenant = scope.tenant().value();
    String statusClass = (seen.status / 100) + "xx";
    Timer.builder("liberty.integration.duration")
        .tag("tenant", tenant)
        .tag("integration", integration)
        .tag("status", statusClass)
        .publishPercentileHistogram()
        .register(meters)
        .record(nanos, TimeUnit.NANOSECONDS);
    if (totals.dbMillis() > 0) {
      meters
          .timer("liberty.integration.db.duration", "tenant", tenant, "integration", integration)
          .record(totals.dbMillis(), TimeUnit.MILLISECONDS);
    }
    if (totals.scriptMillis() > 0) {
      meters
          .timer("liberty.script.duration", "tenant", tenant, "integration", integration)
          .record(totals.scriptMillis(), TimeUnit.MILLISECONDS);
    }
    String requestId = org.slf4j.MDC.get(RequestIdFilter.MDC_KEY);
    audit.record(
        new AuditEvent(
            Instant.now(),
            tenant,
            integration,
            seen.integration.integrationId(),
            seen.integration.versionId(),
            scope.environment().name(),
            request.getMethod(),
            seen.integration.snapshot().integration().route(),
            seen.status,
            nanos / 1_000_000,
            totals.dbMillis(),
            totals.scriptMillis(),
            seen.errorType,
            seen.errorDetail,
            requestId == null ? "-" : requestId,
            authentication.getName()));
  }

  private ResponseEntity<Object> handle(
      HttpServletRequest request, Authentication authentication, TenantScope scope, Seen seen) {
    List<String> path = pathSegments(request, scope.tenant().value());
    RouteTable.Resolution resolution = routes.resolve(scope, request.getMethod(), path);
    if (resolution.match().isEmpty()) {
      throw routeProblem(resolution);
    }
    RouteMatch match = resolution.match().get();
    seen.integration = match.integration();
    String key = match.integration().key();
    if (authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .noneMatch((SCOPE_PREFIX + key)::equals)) {
      throw new ProblemException(
          403, "missing-scope", "Acesso negado", "O token não tem o escopo desta integração");
    }
    if (!"SELECT".equals(match.integration().snapshot().operation().type())) {
      return write(request, scope, match);
    }
    ReadResult result =
        reads.execute(scope, match.integration(), match.pathVariables(), queryOf(request));
    Object body = result.body();
    var afterExecute = match.integration().snapshot().scripts().afterExecute();
    if (afterExecute != null && !afterExecute.isBlank()) {
      scripts.attemptStarted();
      try {
        body =
            scripts.after(
                scope,
                match.integration(),
                scriptParams(match, request),
                null,
                JSON.valueToTree(body));
        scripts.committed(scope); // leitura não tem transação a confirmar
      } finally {
        scripts.attemptEnded();
      }
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  /** Parâmetros declarados pela integração (caminho e query) como o script os recebe. */
  private static Map<String, String> scriptParams(RouteMatch match, HttpServletRequest request) {
    Map<String, String> params = new LinkedHashMap<>(match.pathVariables());
    for (var declared : match.integration().snapshot().request().params()) {
      String value = request.getParameter(declared.name());
      if ("query".equals(declared.in()) && value != null) {
        params.put(declared.name(), value);
      }
    }
    return params;
  }

  private ResponseEntity<Object> write(
      HttpServletRequest request, TenantScope scope, RouteMatch match) {
    int limit = WriteEngine.bodyLimit(match.integration());
    byte[] body = readBody(request, limit);
    WriteResult result =
        writes.execute(
            scope,
            match.integration(),
            new WriteRequest(
                body, match.pathVariables(), queryOf(request), idempotencyKey(request)));
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(result.status())
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_JSON);
    if (result.replayed()) {
      response.header("Idempotent-Replayed", "true");
    }
    return response.body(result.body());
  }

  /** O corpo só é lido se for JSON e couber no limite; nada além do limite entra em memória. */
  private static byte[] readBody(HttpServletRequest request, int limit) {
    String contentType = request.getContentType();
    if (contentType == null
        || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) {
      throw new ProblemException(
          415,
          "unsupported-media-type",
          "Tipo de conteúdo não suportado",
          "Envie o corpo como application/json");
    }
    if (request.getContentLengthLong() > limit) {
      throw payloadTooLarge();
    }
    try {
      byte[] body = request.getInputStream().readNBytes(limit + 1);
      if (body.length > limit) {
        throw payloadTooLarge();
      }
      return body;
    } catch (IOException e) {
      throw new ProblemException(
          400, "unreadable-body", "Corpo ilegível", "Não foi possível ler o corpo da requisição");
    }
  }

  private static ProblemException payloadTooLarge() {
    return new ProblemException(
        413, "payload-too-large", "Corpo grande demais", "O corpo excede o tamanho permitido");
  }

  private static String idempotencyKey(HttpServletRequest request) {
    List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
    if (values.size() > 1) {
      throw ProblemException.validation(
          List.of(
              new ProblemException.Violation(
                  "Idempotency-Key", "repeated_parameter", "Informe o cabeçalho uma única vez")));
    }
    return values.isEmpty() ? null : values.getFirst();
  }

  private static ProblemException routeProblem(RouteTable.Resolution resolution) {
    if (resolution.allowedMethods().isEmpty()) {
      return new ProblemException(
          404,
          "route-not-found",
          "Rota não encontrada",
          "Nenhuma integração publicada atende este caminho");
    }
    return new ProblemException(
        405,
        "method-not-allowed",
        "Método não permitido",
        "O caminho existe, mas não para este método",
        List.of(),
        Map.of(
            "Allow",
            String.join(", ", Set.copyOf(resolution.allowedMethods()).stream().sorted().toList())));
  }

  /**
   * Segmentos do caminho depois de {@code /{tenant}/api}, decodificados; caminhos suspeitos viram
   * "não encontrado".
   */
  private static List<String> pathSegments(HttpServletRequest request, String tenant) {
    String uri = request.getRequestURI().substring(request.getContextPath().length());
    String rest = uri.substring(("/" + tenant + "/api").length());
    List<String> segments = new ArrayList<>();
    if (rest.isEmpty() || rest.equals("/")) {
      return segments;
    }
    String[] parts = rest.substring(1).split("/", -1);
    int count = parts.length;
    if (count > 0 && parts[count - 1].isEmpty()) {
      count--; // uma barra final é tolerada
    }
    for (int i = 0; i < count; i++) {
      String decoded = UriUtils.decode(parts[i], StandardCharsets.UTF_8);
      if (decoded.isEmpty()
          || decoded.equals(".")
          || decoded.equals("..")
          || decoded.contains("/")
          || decoded.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
        throw new ProblemException(
            404,
            "route-not-found",
            "Rota não encontrada",
            "Nenhuma integração publicada atende este caminho");
      }
      segments.add(decoded);
    }
    return segments;
  }

  private static Map<String, List<String>> queryOf(HttpServletRequest request) {
    Map<String, List<String>> query = new LinkedHashMap<>();
    request.getParameterMap().forEach((name, values) -> query.put(name, List.of(values)));
    return query;
  }
}
