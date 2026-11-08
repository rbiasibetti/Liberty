package br.com.liberty.engine.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.outbound.OutboundHttp;
import br.com.liberty.engine.outbound.OutboundProperties;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.queue.QueueProperties;
import br.com.liberty.engine.queue.QueueRepository;
import br.com.liberty.engine.queue.QueueService;
import br.com.liberty.engine.queue.WebhookHandler;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.engine.route.RoutePattern;
import br.com.liberty.infrastructure.catalog.JdbcCatalogIntrospector;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.tenancy.TenantConnectionInfo;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import br.com.liberty.infrastructure.tenancy.TenantRoutingDataSource;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import br.com.liberty.scripts.ScriptExecutor;
import br.com.liberty.scripts.ScriptExecutor.Outcome;
import br.com.liberty.scripts.ScriptExecutor.Status;
import br.com.liberty.snapshot.SnapshotService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os ganchos com um executor falso (tradução de resultados) e o anfitrião contra SQL Server real.
 */
class ScriptHooksTest {

  private static final TenantScope SCOPE =
      new TenantScope(new TenantId("scripts"), EnvironmentKind.HML);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final QueueProperties QUEUE_PROPS =
      new QueueProperties(
          true,
          Duration.ofMillis(100),
          4,
          2,
          3,
          Duration.ZERO,
          Duration.ofSeconds(1),
          Duration.ofSeconds(30),
          1024,
          10,
          2,
          Duration.ofDays(1));
  private static final OutboundHttp OUTBOUND =
      new OutboundHttp(
          new OutboundProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024, 1024, 2, 4),
          (tenant, host) -> host.equals("api.exemplo.com"));
  private static final QueueService QUEUES =
      new QueueService(
          new QueueRepository(new org.springframework.jdbc.core.JdbcTemplate()),
          QUEUE_PROPS,
          java.util.List.of(new WebhookHandler(OUTBOUND)),
          new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  private static final LibertyScriptProperties PROPS =
      new LibertyScriptProperties(
          1,
          "64m",
          Duration.ofSeconds(1),
          1000,
          Duration.ofMillis(100),
          Duration.ofSeconds(1),
          1,
          10,
          Duration.ofMinutes(1),
          5,
          null);

  private static CoreTestDatabase.Scratch db;
  private static TenantPoolRegistry registry;
  private static TenantCatalog catalog;
  private static NamedParameterJdbcTemplate jdbc;

  @BeforeAll
  static void setUp() throws SQLException {
    db = CoreTestDatabase.create();
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE dbo.tb_cliente (id INT PRIMARY KEY, nome NVARCHAR(40) NOT NULL, ativo BIT NOT NULL, saldo DECIMAL(10,2) NOT NULL)");
      st.execute(
          "INSERT INTO dbo.tb_cliente VALUES (1, N'Ana', 1, 10.50), (2, N'Bia', 0, 0), (3, N'Bia', 1, 5)");
    }
    registry =
        new TenantPoolRegistry(
            s -> Optional.of(new TenantConnectionInfo(db.url(), db.user(), "ref/x", 3)),
            r -> db.password(),
            Duration.ofMinutes(5));
    jdbc = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
    catalog =
        new TenantCatalog(
            new JdbcCatalogIntrospector(registry), Duration.ofMinutes(5), Duration.ofSeconds(30));
  }

  @AfterAll
  static void tearDown() {
    registry.destroy();
  }

  private static PublishedIntegration integration(String scripts, String queries) {
    String json =
        "{\"schemaVersion\":1,\"integration\":{\"key\":\"s\",\"name\":\"S\",\"method\":\"POST\",\"route\":\"/s\"},"
            + "\"request\":{\"params\":[],\"bodySchema\":{\"type\":\"object\"}},"
            + "\"operation\":{\"type\":\"INSERT\",\"write\":{\"root\":{\"table\":\"dbo.tb_cliente\",\"fields\":[{\"column\":\"id\",\"source\":\"/id\"},"
            + "{\"column\":\"nome\",\"source\":\"/n\"},{\"column\":\"ativo\",\"constant\":true},{\"column\":\"saldo\",\"constant\":0}]}}},"
            + "\"scripts\":"
            + scripts
            + ",\"namedQueries\":"
            + queries
            + "}";
    var parsed = new SnapshotService().parse(json.getBytes(StandardCharsets.UTF_8));
    return new PublishedIntegration(1, "s", "POST", 1, 1, parsed.model(), RoutePattern.parse("/s"));
  }

  private static final String QUERIES =
      "[{\"name\":\"por_nome\",\"sql\":\"SELECT id, nome, ativo, saldo FROM dbo.tb_cliente WHERE nome = :nome ORDER BY id\",\"params\":[\"nome\"]},"
          + "{\"name\":\"ruim\",\"sql\":\"DELETE FROM dbo.tb_cliente\",\"params\":[]},"
          + "{\"name\":\"duplo\",\"sql\":\"SELECT 1; SELECT 2\",\"params\":[]},"
          + "{\"name\":\"todos\",\"sql\":\"SELECT id FROM dbo.tb_cliente CROSS JOIN (SELECT TOP 400 1 AS x FROM sys.all_objects) a\",\"params\":[]}]";

  private static WorkerScriptHooks hooks(Function<ScriptExecutor.Request, Outcome> executor) {
    return new WorkerScriptHooks(
        (request, host) -> executor.apply(request), catalog, jdbc, PROPS, OUTBOUND, QUEUES);
  }

  private static Outcome ok(String json) {
    return new Outcome(Status.OK, json, null, 0, "", 1);
  }

  private static Outcome failed(Status status, int http, String message) {
    return new Outcome(status, null, message, http, "console", 1);
  }

  private static JsonNode body(String json) {
    return JSON.readTree(json);
  }

  // ---- ganchos -------------------------------------------------------------------------

  @Test
  void withoutScriptsEverythingPassesThrough() {
    var integration = integration("{}", "[]");
    var hooks =
        hooks(
            r -> {
              throw new AssertionError("não deve executar");
            });

    JsonNode in = body("{\"a\":1}");

    assertThat(hooks.before(SCOPE, integration, Map.of(), in)).isSameAs(in);
    assertThat(hooks.after(SCOPE, integration, Map.of(), in, in)).isSameAs(in);
    assertThat(ScriptHooks.NONE.before(SCOPE, integration, Map.of(), in)).isSameAs(in);
    assertThat(ScriptHooks.NONE.after(SCOPE, integration, Map.of(), in, in)).isSameAs(in);
  }

  @Test
  void scriptsReceiveParamsAndBodyAndReplaceThem() {
    var integration = integration("{\"beforeExecute\":\"x\",\"afterExecute\":\"y\"}", "[]");
    var captured = new java.util.ArrayList<ScriptExecutor.Request>();
    var hooks =
        hooks(
            r -> {
              captured.add(r);
              return r.function().equals("beforeExecute") ? ok("{\"novo\":true}") : ok("null");
            });

    JsonNode before = hooks.before(SCOPE, integration, Map.of("p", "1"), body("{\"a\":1}"));
    JsonNode after =
        hooks.after(SCOPE, integration, Map.of("p", "1"), body("{\"a\":1}"), body("{\"r\":2}"));

    assertThat(before.path("novo").asBoolean()).isTrue();
    assertThat(after.path("r").asInt()).isEqualTo(2);
    assertThat(captured.getFirst().inputJson())
        .contains("\"params\":{\"p\":\"1\"}")
        .contains("\"body\":{\"a\":1}");
    assertThat(captured.get(1).inputJson()).contains("\"result\":{\"r\":2}");
  }

  @Test
  void beforeMustReturnAnObjectOrNothing() {
    var integration = integration("{\"beforeExecute\":\"x\"}", "[]");
    JsonNode in = body("{}");

    assertThat(hooks(r -> ok("null")).before(SCOPE, integration, Map.of(), in)).isSameAs(in);
    ProblemException e =
        catchThrowableOfType(
            ProblemException.class,
            () -> hooks(r -> ok("[1]")).before(SCOPE, integration, Map.of(), in));
    assertThat(e.status()).isEqualTo(500);
  }

  @Test
  void outcomesBecomeGenericHttpProblems() {
    var integration = integration("{\"afterExecute\":\"y\"}", "[]");
    JsonNode in = body("{}");
    Map<Status, Integer> expected =
        Map.of(Status.TIMEOUT, 504, Status.SCRIPT_ERROR, 500, Status.WORKER_FAILURE, 503);

    expected.forEach(
        (status, http) -> {
          ProblemException e =
              catchThrowableOfType(
                  ProblemException.class,
                  () ->
                      hooks(r -> failed(status, 0, "detalhe interno secreto"))
                          .after(SCOPE, integration, Map.of(), null, in));
          assertThat(e.status()).isEqualTo(http);
          assertThat(e.getMessage()).doesNotContain("secreto");
        });
    ProblemException rejected =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                hooks(r -> failed(Status.BUSINESS_FAILURE, 409, "estoque"))
                    .after(SCOPE, integration, Map.of(), null, in));
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.getMessage()).isEqualTo("estoque");
  }

  @Test
  void busyPoolsAndTenantBulkheadsAnswer429() throws Exception {
    var integration = integration("{\"afterExecute\":\"y\"}", "[]");
    JsonNode in = body("{}");
    ProblemException busy =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                new WorkerScriptHooks(
                        (r, h) -> {
                          throw new ScriptExecutor.BusyException();
                        },
                        catalog,
                        jdbc,
                        PROPS,
                        OUTBOUND,
                        QUEUES)
                    .after(SCOPE, integration, Map.of(), null, in));
    assertThat(busy.status()).isEqualTo(429);
    assertThat(busy.headers()).containsKey("Retry-After");

    var gate = new java.util.concurrent.CountDownLatch(1);
    var hooks =
        hooks(
            r -> {
              try {
                gate.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ok("null");
            });
    var thread = new Thread(() -> hooks.after(SCOPE, integration, Map.of(), null, in));
    thread.start();
    Thread.sleep(300);
    try {
      ProblemException second =
          catchThrowableOfType(
              ProblemException.class, () -> hooks.after(SCOPE, integration, Map.of(), null, in));
      assertThat(second.status()).isEqualTo(429);
    } finally {
      gate.countDown();
      thread.join();
    }
  }

  // ---- anfitrião ----------------------------------------------------------------------

  private static ScriptHost host() {
    return new ScriptHost(
        SCOPE,
        integration("{}", QUERIES).snapshot().namedQueries(),
        catalog,
        jdbc,
        OUTBOUND.session("acme", true),
        QUEUES.session(SCOPE, true, true));
  }

  @Test
  void httpFetchInTheHostIsSimulatedInTheSandboxAndStillEnforcesTheAllowList() {
    JsonNode out = JSON.readTree(host().fetch("GET", "https://api.exemplo.com/x", "{}", "null"));

    assertThat(out.path("simulated").asBoolean()).isTrue();
    assertThatThrownBy(() -> host().fetch("GET", "https://outro.exemplo.com/", "{}", "null"))
        .hasMessageContaining("não liberado");
  }

  @Test
  void queuePublishInTheHostIsSimulatedAndValidated() {
    assertThat(
            JSON.readTree(host().publish("webhook", "{\"url\":\"https://api.exemplo.com\"}"))
                .path("simulated")
                .asBoolean())
        .isTrue();
    assertThatThrownBy(() -> host().publish("inexistente", "{}"))
        .hasMessageContaining("desconhecida");
    assertThatThrownBy(() -> host().publish("webhook", "[1]")).hasMessageContaining("objeto");
    assertThatThrownBy(() -> host().publish("webhook", "não é json"))
        .hasMessageContaining("inválido");
    assertThatThrownBy(() -> host().publish("webhook", "{\"x\":\"" + "a".repeat(2000) + "\"}"))
        .hasMessageContaining("grande demais");
    var session = QUEUES.session(SCOPE, true, true);
    session.publish("webhook", "{}");
    session.publish("webhook", "{}");
    assertThatThrownBy(() -> session.publish("webhook", "{}")).hasMessageContaining("Limite");
    assertThatThrownBy(() -> QUEUES.session(SCOPE, false, false).publish("webhook", "{}"))
        .as("só em afterExecute")
        .hasMessageContaining("afterExecute");
  }

  @Test
  void scriptsThatCallHttpFetchGetTheOutboundDeadlineOnTop() {
    var seen = new java.util.concurrent.atomic.AtomicReference<Duration>();
    var integration =
        integration("{\"afterExecute\":\"function f(i){ return http.fetch('x'); }\"}", "[]");
    var plain = integration("{\"afterExecute\":\"function f(i){ return i; }\"}", "[]");
    var hooks =
        hooks(
            r -> {
              seen.set(r.timeout());
              return ok("null");
            });

    hooks.after(SCOPE, integration, Map.of(), null, body("{}"));
    assertThat(seen.get()).isEqualTo(Duration.ofSeconds(6));
    hooks.after(SCOPE, plain, Map.of(), null, body("{}"));
    assertThat(seen.get()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void namedQueriesBindParametersAndTypeTheRows() {
    JsonNode rows = JSON.readTree(host().named("por_nome", "{\"nome\":\"Bia\"}"));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).path("id").asInt()).isEqualTo(2);
    assertThat(rows.get(0).path("ativo").asString()).isIn("false", "0");
    assertThat(rows.get(1).path("saldo").decimalValue()).isEqualByComparingTo("5");
  }

  @Test
  void namedQueriesAreStrictlyReadOnlyAndBounded() {
    assertThatThrownBy(() -> host().named("nao_existe", "{}")).hasMessageContaining("inexistente");
    assertThatThrownBy(() -> host().named("ruim", "{}")).hasMessageContaining("SELECT simples");
    assertThatThrownBy(() -> host().named("duplo", "{}")).hasMessageContaining("SELECT simples");
    assertThatThrownBy(() -> host().named("por_nome", "{}")).hasMessageContaining("ausente");
    assertThatThrownBy(() -> host().named("por_nome", "{\"nome\":\"x\",\"extra\":1}"))
        .hasMessageContaining("desconhecido");
    assertThatThrownBy(() -> host().named("por_nome", "{\"nome\":[1]}"))
        .hasMessageContaining("simples");
    assertThatThrownBy(() -> host().named("todos", "{}")).hasMessageContaining("linhas demais");
  }

  @Test
  void lookupFindsOneValueByKey() {
    assertThat(host().lookup("dbo.tb_cliente", "id", "1", "nome")).isEqualTo("\"Ana\"");
    assertThat(host().lookup("dbo.tb_cliente", "id", "99", "nome")).isEqualTo("null");
    assertThat(host().lookup("dbo.tb_cliente", "id", "null", "nome")).isEqualTo("null");
    assertThat(host().lookup("dbo.tb_cliente", "id", "\"2\"", "ativo")).isIn("false", "0");
    assertThat(host().lookup("dbo.tb_cliente", "id", "3", "saldo")).startsWith("5");
    assertThatThrownBy(() -> host().lookup("dbo.tb_cliente", "nome", "\"Bia\"", "id"))
        .hasMessageContaining("mais de uma");
    assertThatThrownBy(() -> host().lookup("dbo.tb_cliente", "id", "\"abc\"", "nome"))
        .hasMessageContaining("inválida");
    assertThatThrownBy(() -> host().lookup("dbo.tb_cliente", "nada", "1", "nome"))
        .hasMessageContaining("inexistente");
  }
}
