package br.com.liberty.engine.script;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.scripts.ScriptExecutor;
import br.com.liberty.snapshot.model.Scripts;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ganchos por trabalhadores isolados. Cada tenant tem um teto de execuções simultâneas (bulkhead);
 * esgotada a espera, 429. Prazo, memória e acesso são contidos no trabalhador (ADR 0017); aqui os
 * resultados viram respostas HTTP com mensagens genéricas, e o detalhe técnico vai só para o log.
 */
public final class WorkerScriptHooks implements ScriptHooks {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerScriptHooks.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final ScriptExecutor executor;
  private final TenantCatalog catalog;
  private final NamedParameterJdbcTemplate jdbc;
  private final LibertyScriptProperties properties;
  private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

  public WorkerScriptHooks(
      ScriptExecutor executor,
      TenantCatalog catalog,
      NamedParameterJdbcTemplate jdbc,
      LibertyScriptProperties properties) {
    this.executor = executor;
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Override
  public JsonNode before(
      TenantScope scope,
      PublishedIntegration integration,
      Map<String, String> params,
      JsonNode body) {
    Scripts scripts = integration.snapshot().scripts();
    if (isBlank(scripts.beforeExecute())) {
      return body;
    }
    ObjectNode input = envelope(params);
    input.set("body", body);
    JsonNode replaced = invoke(scope, integration, scripts.beforeExecute(), "beforeExecute", input);
    if (replaced == null || replaced.isNull()) {
      return body;
    }
    if (!replaced.isObject()) {
      throw scriptError(integration, "beforeExecute devolveu algo que não é um objeto");
    }
    return replaced;
  }

  @Override
  public JsonNode after(
      TenantScope scope,
      PublishedIntegration integration,
      Map<String, String> params,
      JsonNode body,
      JsonNode result) {
    Scripts scripts = integration.snapshot().scripts();
    if (isBlank(scripts.afterExecute())) {
      return result;
    }
    ObjectNode input = envelope(params);
    if (body != null) {
      input.set("body", body);
    }
    input.set("result", result);
    JsonNode replaced = invoke(scope, integration, scripts.afterExecute(), "afterExecute", input);
    return replaced == null || replaced.isNull() ? result : replaced;
  }

  private static ObjectNode envelope(Map<String, String> params) {
    ObjectNode input = MAPPER.createObjectNode();
    ObjectNode values = input.putObject("params");
    new TreeMap<>(params).forEach(values::put);
    return input;
  }

  private JsonNode invoke(
      TenantScope scope,
      PublishedIntegration integration,
      String source,
      String function,
      ObjectNode input) {
    Semaphore bulkhead =
        bulkheads.computeIfAbsent(
            scope.tenant().value(), t -> new Semaphore(properties.tenantConcurrency()));
    boolean acquired = false;
    try {
      acquired = bulkhead.tryAcquire(properties.queueWait().toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw busy();
      }
      var request =
          new ScriptExecutor.Request(
              source,
              function,
              MAPPER.writeValueAsString(input),
              properties.timeout(),
              properties.maxStatements());
      var host = new ScriptHost(scope, integration.snapshot().namedQueries(), catalog, jdbc);
      ScriptExecutor.Outcome outcome = executor.run(request, host);
      return translate(integration, function, outcome);
    } catch (ScriptExecutor.BusyException e) {
      throw busy();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy();
    } finally {
      if (acquired) {
        bulkhead.release();
      }
    }
  }

  private JsonNode translate(
      PublishedIntegration integration, String function, ScriptExecutor.Outcome outcome) {
    ScriptLogs.append(outcome.logs());
    if (!outcome.logs().isEmpty()) {
      LOG.debug("Console do script {} ({}): {}", integration.key(), function, outcome.logs());
    }
    switch (outcome.status()) {
      case OK -> {
        return MAPPER.readTree(outcome.json());
      }
      case TIMEOUT ->
          throw new ProblemException(
              504, "script-timeout", "Tempo esgotado", "O script da integração excedeu o prazo");
      case BUSINESS_FAILURE ->
          throw new ProblemException(
              outcome.httpStatus(), "script-rejected", "Operação recusada", outcome.message());
      case WORKER_FAILURE -> {
        LOG.error("Trabalhador de script falhou na integração {}", integration.key());
        throw new ProblemException(
            503,
            "script-unavailable",
            "Serviço indisponível",
            "O processamento do script está indisponível; tente novamente",
            List.of(),
            Map.of("Retry-After", "1"));
      }
      default -> throw scriptError(integration, outcome.message());
    }
  }

  private static ProblemException scriptError(PublishedIntegration integration, String detail) {
    LOG.error("Script da integração {} falhou: {}", integration.key(), detail);
    return new ProblemException(
        500, "script-error", "Erro interno", "O script da integração falhou");
  }

  private static ProblemException busy() {
    return new ProblemException(
        429,
        "too-many-requests",
        "Muitas requisições",
        "Há scripts demais em execução; tente novamente",
        List.of(),
        Map.of("Retry-After", "1"));
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }
}
