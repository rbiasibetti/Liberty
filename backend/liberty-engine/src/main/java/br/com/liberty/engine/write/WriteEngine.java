package br.com.liberty.engine.write;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.engine.read.InputParser;
import br.com.liberty.engine.read.ParsedInput;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.engine.script.ScriptHooks;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.catalog.UnknownDatabaseObjectException;
import br.com.liberty.infrastructure.tenancy.TenantUnavailableException;
import br.com.liberty.snapshot.CanonicalJson;
import br.com.liberty.snapshot.model.Snapshot;
import br.com.liberty.snapshot.validation.SchemaValidator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Schema;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executa uma integração de escrita: confere parâmetros e corpo (tamanho, JSON Schema, tipos),
 * resolve a árvore no catálogo do tenant, aplica a idempotência e grava tudo numa única transação
 * no banco do tenant. Qualquer falha desfaz a árvore inteira. Erros viram {@link ProblemException}
 * com mensagens genéricas; os detalhes vão só para o log.
 */
public final class WriteEngine {

  private static final Logger LOG = LoggerFactory.getLogger(WriteEngine.class);

  public static final int DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024;
  private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,200}");

  private final TenantCatalog catalog;
  private final WritePlanner planner;
  private final WriteExecutor executor;
  private final Materializer materializer;
  private final TransactionTemplate transaction;
  private final IdempotencyService idempotency;
  private final ScriptHooks scripts;
  private final Cache<Long, Schema> schemas = Caffeine.newBuilder().maximumSize(500).build();

  public WriteEngine(
      TenantCatalog catalog,
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate transaction,
      IdempotencyService idempotency,
      FieldTransformer transformer,
      int maxRows) {
    this(catalog, jdbc, transaction, idempotency, transformer, maxRows, ScriptHooks.NONE);
  }

  public WriteEngine(
      TenantCatalog catalog,
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate transaction,
      IdempotencyService idempotency,
      FieldTransformer transformer,
      int maxRows,
      ScriptHooks scripts) {
    this.scripts = scripts;
    this.catalog = catalog;
    this.planner = new WritePlanner(catalog);
    this.executor = new WriteExecutor(jdbc);
    this.materializer = new Materializer(transformer, maxRows);
    this.transaction = transaction;
    this.idempotency = idempotency;
  }

  /** Limite do corpo desta integração, para o canal HTTP ler sem passar dele. */
  public static int bodyLimit(PublishedIntegration integration) {
    Integer configured = integration.snapshot().request().maxBodyBytes();
    return configured == null
        ? DEFAULT_MAX_BODY_BYTES
        : Math.min(configured, DEFAULT_MAX_BODY_BYTES);
  }

  public WriteResult execute(
      TenantScope scope, PublishedIntegration integration, WriteRequest request) {
    Snapshot snapshot = integration.snapshot();
    String type = snapshot.operation().type();
    ParsedInput input = InputParser.parse(snapshot, request.pathVariables(), request.query());
    String key = idempotencyKey(snapshot, request.idempotencyKey());
    JsonNode body = BodyReader.read(request.body(), bodyLimit(integration));
    validateSchema(integration, body);
    JsonNode original = body;
    body = scripts.before(scope, integration, input.values(), body);
    RowData data = materialize(scope, integration, type, body, input);

    IdempotencyService.Proceed reservation = null;
    if (key != null) {
      byte[] hash = requestHash(input, original);
      var decision = idempotency.begin(scope, integration.integrationId(), key, hash);
      if (decision instanceof IdempotencyService.Replay replay) {
        return new WriteResult(replay.status(), parse(replay.body()), true);
      }
      reservation = (IdempotencyService.Proceed) decision;
    }
    WriteResult result;
    try {
      result = run(scope, integration, type, data, input.values(), body);
    } catch (RuntimeException e) {
      if (reservation != null) {
        idempotency.release(reservation);
      }
      throw e;
    }
    if (reservation != null) {
      idempotency.complete(reservation, result.status(), CanonicalJson.write(result.body()));
    }
    return result;
  }

  // ---- entrada ---------------------------------------------------------------------------

  private static String idempotencyKey(Snapshot snapshot, String header) {
    if (header == null || header.isEmpty()) {
      if (snapshot.idempotency().required()) {
        throw ProblemException.validation(
            List.of(
                new Violation("Idempotency-Key", "required", "Esta integração exige o cabeçalho")));
      }
      return null;
    }
    if (!KEY.matcher(header).matches()) {
      throw ProblemException.validation(
          List.of(
              new Violation(
                  "Idempotency-Key", "invalid_value", "Use de 1 a 200 caracteres ASCII visíveis")));
    }
    return header;
  }

  private void validateSchema(PublishedIntegration integration, JsonNode body) {
    JsonNode schemaNode = integration.snapshot().request().bodySchema();
    if (schemaNode == null || schemaNode.isNull()) {
      return;
    }
    Schema schema = schemas.get(integration.versionId(), id -> SchemaValidator.compile(schemaNode));
    var errors = SchemaValidator.toFieldErrors(schema.validate(body));
    if (!errors.isEmpty()) {
      throw ProblemException.validation(
          errors.stream()
              .limit(50)
              .map(e -> new Violation(e.path(), e.code(), e.message()))
              .toList());
    }
  }

  private RowData materialize(
      TenantScope scope,
      PublishedIntegration integration,
      String type,
      JsonNode body,
      ParsedInput input) {
    WritePlan plan;
    try {
      plan = planner.plan(scope, integration.snapshot().operation().write().root(), type);
    } catch (UnknownDatabaseObjectException e) {
      catalog.invalidate(scope);
      LOG.error(
          "Integração {} ({}) referencia objeto inexistente: {}",
          integration.key(),
          scope,
          e.getMessage());
      throw schemaChanged();
    } catch (WritePlanner.ConfigurationException
        | br.com.liberty.engine.read.ValueBinder.UnsupportedColumnException e) {
      LOG.error("Integração {} ({}) mal configurada: {}", integration.key(), scope, e.getMessage());
      throw misconfigured();
    } catch (TenantUnavailableException e) {
      throw tenantUnavailable(scope, e);
    }
    try {
      return materializer.build(plan, body, input.values(), type);
    } catch (br.com.liberty.engine.read.ValueBinder.UnsupportedColumnException
        | IllegalStateException e) {
      LOG.error("Integração {} ({}) mal configurada: {}", integration.key(), scope, e.getMessage());
      throw misconfigured();
    }
  }

  private static byte[] requestHash(ParsedInput input, JsonNode body) {
    ObjectNode envelope = BodyReader.mapper().createObjectNode();
    ObjectNode params = envelope.putObject("params");
    new TreeMap<>(input.values()).forEach(params::put);
    envelope.set("body", body);
    return CanonicalJson.sha256(envelope);
  }

  private static JsonNode parse(String json) {
    return BodyReader.mapper().readTree(json);
  }

  // ---- execução --------------------------------------------------------------------------

  /** Resultado da gravação e a resposta já passada pelo gancho {@code afterExecute}. */
  private record Done(WriteExecutor.Outcome outcome, JsonNode response) {}

  /**
   * O {@code afterExecute} roda ainda dentro da transação: se o script recusar a operação, nada é
   * confirmado.
   */
  private WriteResult run(
      TenantScope scope,
      PublishedIntegration integration,
      String type,
      RowData data,
      Map<String, String> params,
      JsonNode body) {
    try {
      Done done =
          TenantContext.supplyAs(
              scope,
              () ->
                  transaction.execute(
                      status -> {
                        WriteExecutor.Outcome outcome =
                            switch (type) {
                              case "INSERT" -> executor.insert(data);
                              case "UPSERT" -> executor.upsert(data);
                              case "UPDATE" -> executor.updateOnly(data);
                              default ->
                                  throw new IllegalStateException("Operação não é de escrita");
                            };
                        return new Done(
                            outcome,
                            scripts.after(scope, integration, params, body, outcome.body()));
                      }));
      return new WriteResult(
          statusOf(integration.snapshot(), done.outcome()), done.response(), false);
    } catch (TenantUnavailableException e) {
      throw tenantUnavailable(scope, e);
    } catch (TransactionTimedOutException e) {
      throw timeout();
    } catch (DataAccessException e) {
      throw translate(scope, integration, e);
    }
  }

  private static int statusOf(Snapshot snapshot, WriteExecutor.Outcome outcome) {
    Integer configured = snapshot.response().successStatus();
    if (configured != null) {
      return configured;
    }
    return outcome.created() ? 201 : 200;
  }

  private ProblemException translate(
      TenantScope scope, PublishedIntegration integration, DataAccessException e) {
    if (catalog.onDatabaseError(scope, e)) {
      return schemaChanged();
    }
    var known = SqlErrors.translate(e);
    if (known.isPresent()) {
      LOG.info(
          "Escrita {} ({}) recusada pelo banco: {}", integration.key(), scope, known.get().type());
      return known.get();
    }
    if (e instanceof QueryTimeoutException || e.getCause() instanceof SQLTimeoutException) {
      return timeout();
    }
    LOG.error("Falha ao gravar no banco do tenant {} ({})", scope, integration.key(), e);
    return new ProblemException(
        500, "internal", "Erro interno", "Falha ao gravar no banco do cliente");
  }

  private static ProblemException timeout() {
    return new ProblemException(
        504, "query-timeout", "Tempo esgotado", "A gravação no banco do cliente excedeu o prazo");
  }

  private static ProblemException schemaChanged() {
    return new ProblemException(
        503,
        "schema-changed",
        "Integração indisponível",
        "A estrutura do banco do cliente mudou; tente novamente em instantes");
  }

  private static ProblemException misconfigured() {
    return new ProblemException(
        500, "internal", "Erro interno", "A integração está mal configurada");
  }

  private static ProblemException tenantUnavailable(
      TenantScope scope, TenantUnavailableException e) {
    LOG.warn("Banco do tenant {} indisponível: {}", scope, e.getMessage());
    return new ProblemException(
        503, "tenant-unavailable", "Serviço indisponível", "O banco do cliente está indisponível");
  }
}
