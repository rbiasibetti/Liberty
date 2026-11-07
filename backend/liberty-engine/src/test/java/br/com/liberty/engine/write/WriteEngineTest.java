package br.com.liberty.engine.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.engine.route.RoutePattern;
import br.com.liberty.infrastructure.catalog.JdbcCatalogIntrospector;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.tenancy.TenantConnectionInfo;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import br.com.liberty.infrastructure.tenancy.TenantRoutingDataSource;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import br.com.liberty.snapshot.SnapshotService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** O motor de escrita contra SQL Server real, com a mesma fiação de produção. */
class WriteEngineTest {

  private static final TenantScope SCOPE =
      new TenantScope(new TenantId("escrita"), EnvironmentKind.HML);
  private static final SnapshotService SNAPSHOTS = new SnapshotService();
  private static final AtomicLong VERSIONS = new AtomicLong();

  private static CoreTestDatabase.Scratch db;
  private static TenantPoolRegistry registry;
  private static WriteEngine engine;
  private static InMemoryIdempotency idempotency;

  /** Armazém de idempotência em memória: a lógica do JPA é coberta no teste HTTP. */
  static final class InMemoryIdempotency implements IdempotencyService {
    final Map<String, Object[]> entries = new ConcurrentHashMap<>();

    @Override
    public Decision begin(TenantScope scope, long integrationId, String key, byte[] hash) {
      String id = integrationId + "/" + key;
      Object[] fresh = {hash, "IN_PROGRESS", 0, null};
      Object[] previous = entries.putIfAbsent(id, fresh);
      if (previous == null) {
        return new Proceed(id);
      }
      if (!Arrays.equals((byte[]) previous[0], hash)) {
        throw new ProblemException(409, "idempotency-key-reuse", "Reuso", "outra requisição");
      }
      if ("DONE".equals(previous[1])) {
        return new Replay((Integer) previous[2], (String) previous[3]);
      }
      throw new ProblemException(409, "idempotency-in-progress", "Em andamento", "em andamento");
    }

    @Override
    public void complete(Proceed reservation, int status, String body) {
      entries.put(
          (String) reservation.token(),
          new Object[] {entries.get((String) reservation.token())[0], "DONE", status, body});
    }

    @Override
    public void release(Proceed reservation) {
      entries.remove((String) reservation.token());
    }
  }

  private static void exec(String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
    }
  }

  private static long count(String table) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String scalar(String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  @BeforeAll
  static void setUp() throws SQLException {
    db = CoreTestDatabase.create();
    exec(
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, numero VARCHAR(30) NOT NULL UNIQUE,"
            + " cliente_id INT NOT NULL, vl_total DECIMAL(18,2) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ABERTO',"
            + " obs VARCHAR(10) NULL)",
        "CREATE TABLE dbo.tb_item (id BIGINT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL REFERENCES dbo.tb_pedido(id),"
            + " produto VARCHAR(30) NOT NULL, qtd INT NOT NULL CONSTRAINT ck_qtd CHECK (qtd > 0), vl DECIMAL(18,2) NOT NULL)",
        "CREATE TABLE dbo.tb_lote (id BIGINT IDENTITY PRIMARY KEY, item_id BIGINT NOT NULL REFERENCES dbo.tb_item(id),"
            + " codigo VARCHAR(20) NOT NULL)",
        "CREATE TABLE dbo.tb_tag (id BIGINT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL REFERENCES dbo.tb_pedido(id),"
            + " nome VARCHAR(20) NOT NULL, cor VARCHAR(10) NULL)",
        "CREATE TABLE dbo.tb_cliente (codigo VARCHAR(20) PRIMARY KEY, nome NVARCHAR(50) NOT NULL, email VARCHAR(80) NULL,"
            + " versao INT NOT NULL DEFAULT 0)",
        "CREATE TABLE dbo.tb_log (id INT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL)",
        "CREATE TABLE dbo.tb_uuid (id UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID() PRIMARY KEY, nome VARCHAR(20) NOT NULL)",
        "CREATE TABLE dbo.tb_auto (id INT IDENTITY PRIMARY KEY, criado DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME())",
        "CREATE VIEW dbo.vw_pedido AS SELECT id, numero FROM dbo.tb_pedido",
        "CREATE TRIGGER dbo.trg_pedido_log ON dbo.tb_pedido AFTER INSERT AS BEGIN INSERT INTO dbo.tb_log (pedido_id)"
            + " SELECT id FROM inserted END");
    registry =
        new TenantPoolRegistry(
            scope -> Optional.of(new TenantConnectionInfo(db.url(), db.user(), "ref/x", 6)),
            reference -> db.password(),
            Duration.ofMinutes(10));
    var jdbc = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
    var tx =
        new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    tx.setTimeout(30);
    var catalog =
        new TenantCatalog(
            new JdbcCatalogIntrospector(registry), Duration.ofMinutes(5), Duration.ofSeconds(30));
    idempotency = new InMemoryIdempotency();
    engine = new WriteEngine(catalog, jdbc, tx, idempotency, FieldTransformer.NONE, 50_000);
  }

  @AfterAll
  static void tearDown() {
    registry.destroy();
  }

  @BeforeEach
  void clean() throws SQLException {
    exec(
        "DELETE FROM dbo.tb_lote",
        "DELETE FROM dbo.tb_item",
        "DELETE FROM dbo.tb_tag",
        "DELETE FROM dbo.tb_log",
        "DELETE FROM dbo.tb_pedido",
        "DELETE FROM dbo.tb_cliente",
        "DELETE FROM dbo.tb_uuid",
        "DELETE FROM dbo.tb_auto");
    idempotency.entries.clear();
  }

  // ---- fixtures ---------------------------------------------------------------------------

  private static String j(String quoted) {
    return quoted.replace('\'', '"');
  }

  private static PublishedIntegration integration(
      String type, String route, String params, String bodySchema, String root, String extra) {
    String request =
        "{\"params\":"
            + params
            + ",\"bodySchema\":"
            + (bodySchema == null ? "{\"type\":\"object\"}" : bodySchema)
            + "}";
    String json =
        "{\"schemaVersion\":1,\"integration\":{\"key\":\"escrita\",\"name\":\"Escrita\",\"method\":\""
            + ("UPDATE".equals(type) ? "PATCH" : "POST")
            + "\",\"route\":\""
            + route
            + "\"},\"request\":"
            + request
            + ",\"operation\":{\"type\":\""
            + type
            + "\",\"write\":{\"root\":"
            + root
            + "}}"
            + extra
            + "}";
    br.com.liberty.snapshot.ParsedSnapshot parsed;
    try {
      parsed = SNAPSHOTS.parse(json.getBytes(StandardCharsets.UTF_8));
    } catch (br.com.liberty.snapshot.validation.SnapshotValidationException e) {
      throw new IllegalStateException("Fixture inválido: " + e.errors() + " em " + json, e);
    }
    long id = VERSIONS.incrementAndGet();
    return new PublishedIntegration(
        id, "escrita", "POST", id, 1, parsed.model(), RoutePattern.parse(route));
  }

  private static final String LOTE =
      j(
          "{'table':'dbo.tb_lote','arrayPath':'/lotes','foreignKey':'item_id','fields':[{'column':'codigo','source':'/codigo'}]}");

  private static final String ITEM =
      j(
          "{'table':'dbo.tb_item','arrayPath':'/itens','primaryKey':'id','foreignKey':'pedido_id','fields':["
              + "{'column':'produto','source':'/produto'},{'column':'qtd','source':'/qtd'},{'column':'vl','source':'/valor'}],"
              + "'children':["
              + LOTE.replace('"', '\'')
              + "]}");

  private static final String PEDIDO_FIELDS =
      j(
          "[{'column':'numero','source':'/numero'},{'column':'cliente_id','source':'/clienteId'},"
              + "{'column':'vl_total','source':'/total'},{'column':'status','source':'/status'}]");

  private static String pedidoRoot(String children) {
    return "{\"table\":\"dbo.tb_pedido\",\"primaryKey\":\"id\",\"fields\":"
        + PEDIDO_FIELDS
        + ",\"children\":["
        + children
        + "]}";
  }

  /** Master-detail de três níveis: pedido, itens e lotes de cada item. */
  private static PublishedIntegration threeLevels() {
    return integration("INSERT", "/pedidos", "[]", null, pedidoRoot(ITEM), "");
  }

  private static WriteResult run(PublishedIntegration integration, String body) {
    return run(integration, body, Map.of(), null);
  }

  private static WriteResult run(
      PublishedIntegration integration, String body, Map<String, String> path, String key) {
    return engine.execute(
        SCOPE,
        integration,
        new WriteRequest(body.getBytes(StandardCharsets.UTF_8), path, Map.of(), key));
  }

  private static ProblemException failure(PublishedIntegration integration, String body) {
    return catchThrowableOfType(
        ProblemException.class, () -> run(integration, body.replace('\'', '"')));
  }

  private static String pedidoBody(String numero, String itens) {
    return j("{'numero':'" + numero + "','clienteId':7,'total':150.75,'itens':[" + itens + "]}");
  }

  private static void assertNothingPersisted() throws SQLException {
    assertThat(count("dbo.tb_pedido")).isZero();
    assertThat(count("dbo.tb_item")).isZero();
    assertThat(count("dbo.tb_lote")).isZero();
    assertThat(count("dbo.tb_log")).isZero();
  }

  // ---- master-detail ----------------------------------------------------------------------

  @Test
  void threeLevelsAreWrittenTogetherWithKeysLinkedAndTheTriggerFired() throws SQLException {
    WriteResult result =
        run(
            threeLevels(),
            pedidoBody(
                "P-1",
                j(
                    "{'produto':'A','qtd':2,'valor':10.5,'lotes':[{'codigo':'L1'},{'codigo':'L2'}]},"
                        + "{'produto':'B','qtd':1,'valor':99.99,'lotes':[{'codigo':'L3'}]},"
                        + "{'produto':'C','qtd':5,'valor':1}")));

    assertThat(result.status()).isEqualTo(201);
    assertThat(result.replayed()).isFalse();
    assertThat(result.body().path("key").path("id").asLong()).isPositive();
    assertThat(result.body().path("rows").path("tb_pedido").asInt()).isEqualTo(1);
    assertThat(result.body().path("rows").path("tb_item").asInt()).isEqualTo(3);
    assertThat(result.body().path("rows").path("tb_lote").asInt()).isEqualTo(3);
    assertThat(count("dbo.tb_pedido")).isEqualTo(1);
    assertThat(count("dbo.tb_item")).isEqualTo(3);
    assertThat(count("dbo.tb_lote")).isEqualTo(3);
    assertThat(scalar("SELECT COUNT(*) FROM dbo.tb_log")).isEqualTo("1");
    long pedido = result.body().path("key").path("id").asLong();
    assertThat(scalar("SELECT COUNT(*) FROM dbo.tb_item WHERE pedido_id = " + pedido))
        .isEqualTo("3");
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM dbo.tb_lote l JOIN dbo.tb_item i ON i.id = l.item_id"
                    + " WHERE i.pedido_id = "
                    + pedido
                    + " AND i.produto = 'A'"))
        .isEqualTo("2");
    assertThat(scalar("SELECT status FROM dbo.tb_pedido")).isEqualTo("ABERTO");
  }

  @Test
  void failureAtTheThirdLevelUndoesTheWholeTree() throws SQLException {
    ProblemException e =
        failure(
            threeLevels(),
            pedidoBody(
                "P-2",
                "{'produto':'A','qtd':2,'valor':1,'lotes':[{'codigo':'ok'}]},"
                    + "{'produto':'B','qtd':1,'valor':1,'lotes':[{'codigo':'ok'},{'codigo':'um-codigo-bem-maior-que-vinte'}]}"));

    assertThat(e.status()).isEqualTo(422);
    assertThat(e.type()).endsWith("unprocessable");
    assertNothingPersisted();
  }

  @Test
  void failureAtTheSecondLevelUndoesTheWholeTree() throws SQLException {
    ProblemException e =
        failure(
            threeLevels(),
            pedidoBody(
                "P-3",
                "{'produto':'A','qtd':2,'valor':1,'lotes':[{'codigo':'ok'}]},"
                    + "{'produto':'B','qtd':0,'valor':1,'lotes':[{'codigo':'ok'}]}"));

    assertThat(e.status()).isEqualTo(409);
    assertThat(e.type()).endsWith("constraint-violation");
    assertThat(e.getMessage()).doesNotContain("ck_qtd").doesNotContain("tb_item");
    assertNothingPersisted();
  }

  @Test
  void duplicateKeyIsAConflictAndLeavesNothingBehind() throws SQLException {
    run(threeLevels(), pedidoBody("DUP", "{'produto':'A','qtd':1,'valor':1}".replace('\'', '"')));
    long before = count("dbo.tb_item");

    ProblemException e =
        failure(threeLevels(), pedidoBody("DUP", "{'produto':'B','qtd':1,'valor':1}"));

    assertThat(e.status()).isEqualTo(409);
    assertThat(e.type()).endsWith("conflict");
    assertThat(count("dbo.tb_pedido")).isEqualTo(1);
    assertThat(count("dbo.tb_item")).isEqualTo(before);
  }

  @Test
  void invalidInputIsReportedFieldByFieldBeforeTouchingTheDatabase() throws SQLException {
    ProblemException e =
        failure(
            threeLevels(),
            j(
                "{'numero':'P-4','clienteId':'abc','total':1,'itens':[{'produto':'A','qtd':1,'valor':1},"
                    + "{'qtd':'x','valor':1,'lotes':{}},7]}"));

    assertThat(e.status()).isEqualTo(400);
    assertThat(e.violations())
        .extracting(ProblemException.Violation::parameter)
        .contains("/clienteId", "/itens/1/produto", "/itens/1/qtd", "/itens/1/lotes", "/itens/2");
    assertNothingPersisted();
  }

  @Test
  void leafChildrenGoInBatchesAndKeepTheirOrder() throws SQLException {
    var integration =
        integration(
            "INSERT",
            "/pedidos",
            "[]",
            null,
            pedidoRoot(
                j(
                    "{'table':'dbo.tb_tag','arrayPath':'/tags','foreignKey':'pedido_id','fields':["
                        + "{'column':'nome','source':'/nome'},{'column':'cor','source':'/cor'}]}")),
            "");
    StringBuilder tags = new StringBuilder();
    for (int i = 0; i < 1203; i++) {
      tags.append(i == 0 ? "" : ",")
          .append("{\"nome\":\"t")
          .append(i)
          .append('"')
          .append(i % 7 == 0 ? ",\"cor\":\"azul\"" : "")
          .append('}');
    }

    WriteResult result =
        run(
            integration,
            "{\"numero\":\"P-5\",\"clienteId\":1,\"total\":1,\"tags\":[" + tags + "]}");

    assertThat(result.body().path("rows").path("tb_tag").asInt()).isEqualTo(1203);
    assertThat(count("dbo.tb_tag")).isEqualTo(1203);
    assertThat(scalar("SELECT COUNT(*) FROM dbo.tb_tag WHERE cor = 'azul'")).isEqualTo("172");
    assertThat(scalar("SELECT TOP 1 nome FROM dbo.tb_tag ORDER BY id DESC")).isEqualTo("t1202");
  }

  @Test
  void nullsAndAbsentValuesAreDistinct() throws SQLException {
    var integration =
        integration(
            "INSERT",
            "/pedidos",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_pedido','fields':[{'column':'numero','source':'/numero'},"
                    + "{'column':'cliente_id','constant':3},{'column':'vl_total','constant':'10.00'},"
                    + "{'column':'status','source':'/status'},{'column':'obs','source':'/obs'}]}"),
            "");

    WriteResult sem = run(integration, "{\"numero\":\"A\"}");
    run(integration, "{\"numero\":\"B\",\"obs\":null}");
    run(integration, "{\"numero\":\"C\",\"obs\":\"x\",\"status\":\"PAGO\"}");

    assertThat(sem.body().has("key")).isFalse();
    assertThat(scalar("SELECT status FROM dbo.tb_pedido WHERE numero = 'A'")).isEqualTo("ABERTO");
    assertThat(scalar("SELECT obs FROM dbo.tb_pedido WHERE numero = 'B'")).isNull();
    assertThat(scalar("SELECT status FROM dbo.tb_pedido WHERE numero = 'C'")).isEqualTo("PAGO");
    assertThat(scalar("SELECT cliente_id FROM dbo.tb_pedido WHERE numero = 'C'")).isEqualTo("3");
  }

  @Test
  void generatedKeysOfOtherTypesAreCapturedAndDefaultsOnlyRowsWork() throws SQLException {
    var uuid =
        integration(
            "INSERT",
            "/u",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_uuid','primaryKey':'id','fields':[{'column':'nome','source':'/nome'}]}"),
            "");
    var auto =
        integration(
            "INSERT",
            "/a",
            "[]",
            null,
            j("{'table':'dbo.tb_auto','primaryKey':'id','fields':[]}")
                .replace(
                    "\"fields\":[]",
                    "\"fields\":[{\"column\":\"criado\",\"constant\":\"2026-01-01T00:00:00\"}]"),
            "");

    WriteResult u = run(uuid, "{\"nome\":\"x\"}");
    WriteResult a = run(auto, "{}");

    assertThat(u.body().path("key").path("id").asString()).hasSize(36);
    assertThat(a.body().path("key").path("id").asInt()).isPositive();
    assertThat(count("dbo.tb_uuid")).isEqualTo(1);
    assertThat(count("dbo.tb_auto")).isEqualTo(1);
  }

  @Test
  void hostileTextIsStoredLiterallyNeverExecuted() throws SQLException {
    String hostile = "'; DROP TABLE dbo.tb_log;--";

    run(
        threeLevels(),
        "{\"numero\":\"" + hostile.replace("'", "\\u0027") + "\",\"clienteId\":1,\"total\":1}");

    assertThat(scalar("SELECT numero FROM dbo.tb_pedido")).isEqualTo(hostile);
    assertThat(count("dbo.tb_log")).isEqualTo(1);
  }

  // ---- UPSERT / UPDATE --------------------------------------------------------------------

  private static PublishedIntegration upsert() {
    return integration(
        "UPSERT",
        "/clientes/{codigo}",
        j("[{'name':'codigo','in':'path','type':'string'}]"),
        null,
        j(
            "{'table':'dbo.tb_cliente','keyColumns':['codigo'],'fields':[{'column':'codigo','param':'codigo'},"
                + "{'column':'nome','source':'/nome'},{'column':'email','source':'/email'}]}"),
        "");
  }

  @Test
  void upsertInsertsThenUpdatesAndKeepsAbsentColumns() throws SQLException {
    var path = Map.of("codigo", "C1");

    WriteResult first = run(upsert(), "{\"nome\":\"Ana\",\"email\":\"a@x\"}", path, null);
    WriteResult second = run(upsert(), "{\"nome\":\"Ana Maria\"}", path, null);

    assertThat(first.status()).isEqualTo(201);
    assertThat(first.body().path("result").asString()).isEqualTo("inserted");
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body().path("result").asString()).isEqualTo("updated");
    assertThat(second.body().path("key").path("codigo").asString()).isEqualTo("C1");
    assertThat(count("dbo.tb_cliente")).isEqualTo(1);
    assertThat(scalar("SELECT nome FROM dbo.tb_cliente")).isEqualTo("Ana Maria");
    assertThat(scalar("SELECT email FROM dbo.tb_cliente")).isEqualTo("a@x");
  }

  @Test
  void concurrentUpsertsOfTheSameNewKeyProduceOneRowAndNoErrors() throws Exception {
    var path = Map.of("codigo", "RACE");
    List<Future<WriteResult>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
      for (int i = 0; i < 10; i++) {
        String body = "{\"nome\":\"n" + i + "\"}";
        futures.add(pool.submit(() -> run(upsert(), body, path, null)));
      }
      int created = 0;
      for (Future<WriteResult> f : futures) {
        created += f.get().status() == 201 ? 1 : 0;
      }
      assertThat(created).isEqualTo(1);
    }
    assertThat(count("dbo.tb_cliente")).isEqualTo(1);
  }

  @Test
  void updateChangesOnlyTheMappedPresentColumnsAndMissingRowsAre404() throws SQLException {
    exec("INSERT INTO dbo.tb_cliente (codigo, nome, email) VALUES ('U1', N'Velho', 'v@x')");
    var update =
        integration(
            "UPDATE",
            "/clientes/{codigo}",
            j("[{'name':'codigo','in':'path','type':'string'}]"),
            null,
            j(
                "{'table':'dbo.tb_cliente','keyColumns':['codigo'],'fields':[{'column':'codigo','param':'codigo'},"
                    + "{'column':'nome','source':'/nome'},{'column':'email','source':'/email'}]}"),
            "");

    WriteResult ok = run(update, "{\"email\":null}", Map.of("codigo", "U1"), null);
    ProblemException missing =
        catchThrowableOfType(
            ProblemException.class,
            () -> run(update, "{\"nome\":\"x\"}", Map.of("codigo", "NAO"), null));

    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body().path("updated").asInt()).isEqualTo(1);
    assertThat(scalar("SELECT nome FROM dbo.tb_cliente")).isEqualTo("Velho");
    assertThat(scalar("SELECT email FROM dbo.tb_cliente")).isNull();
    assertThat(missing.status()).isEqualTo(404);
  }

  // ---- idempotência ----------------------------------------------------------------------

  @Test
  void repeatingTheSameKeyDoesNotDuplicateAndReplaysTheResponse() throws SQLException {
    var integration = threeLevels();
    String body = pedidoBody("IDEM", "{'produto':'A','qtd':1,'valor':1}".replace('\'', '"'));

    WriteResult first = run(integration, body, Map.of(), "chave-1");
    WriteResult repeat = run(integration, body, Map.of(), "chave-1");

    assertThat(first.replayed()).isFalse();
    assertThat(repeat.replayed()).isTrue();
    assertThat(repeat.status()).isEqualTo(201);
    assertThat(br.com.liberty.snapshot.CanonicalJson.write(repeat.body()))
        .isEqualTo(br.com.liberty.snapshot.CanonicalJson.write(first.body()));
    assertThat(count("dbo.tb_pedido")).isEqualTo(1);
    assertThat(count("dbo.tb_item")).isEqualTo(1);
    assertThat(count("dbo.tb_log")).isEqualTo(1);
  }

  @Test
  void theSameKeyWithAnotherBodyIsRejected() {
    var integration = threeLevels();
    run(integration, pedidoBody("K1", "{\"produto\":\"A\",\"qtd\":1,\"valor\":1}"), Map.of(), "k");

    ProblemException e =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                run(
                    integration,
                    pedidoBody("K2", "{\"produto\":\"A\",\"qtd\":1,\"valor\":1}"),
                    Map.of(),
                    "k"));

    assertThat(e.status()).isEqualTo(409);
    assertThat(e.type()).endsWith("idempotency-key-reuse");
  }

  @Test
  void aFailedWriteReleasesTheKeySoARetryCanSucceed() throws SQLException {
    var integration = threeLevels();
    String bad = pedidoBody("R1", "{\"produto\":\"A\",\"qtd\":0,\"valor\":1}");
    ProblemException e =
        catchThrowableOfType(
            ProblemException.class, () -> run(integration, bad, Map.of(), "retry"));
    assertThat(e.status()).isEqualTo(409);
    assertThat(idempotency.entries).isEmpty();

    // a mesma chave com um corpo diferente (corrigido) volta a valer
    WriteResult ok =
        run(
            integration,
            pedidoBody("R1", "{\"produto\":\"A\",\"qtd\":1,\"valor\":1}"),
            Map.of(),
            "retry");

    assertThat(ok.status()).isEqualTo(201);
    assertThat(count("dbo.tb_pedido")).isEqualTo(1);
  }

  @Test
  void invalidRequestsNeverReserveAKey() {
    var integration = threeLevels();

    catchThrowableOfType(
        ProblemException.class, () -> run(integration, "{\"numero\":1}", Map.of(), "so-valida"));

    assertThat(idempotency.entries).isEmpty();
  }

  @Test
  void requiredIdempotencyKeyIsEnforcedAndMalformedKeysAreRejected() {
    var strict =
        integration(
            "INSERT",
            "/pedidos",
            "[]",
            null,
            pedidoRoot(""),
            ",\"idempotency\":{\"required\":true}");
    String body = "{\"numero\":\"S\",\"clienteId\":1,\"total\":1}";

    ProblemException missing =
        catchThrowableOfType(ProblemException.class, () -> run(strict, body));
    ProblemException malformed =
        catchThrowableOfType(
            ProblemException.class, () -> run(strict, body, Map.of(), "com espaço"));
    ProblemException tooLong =
        catchThrowableOfType(
            ProblemException.class, () -> run(strict, body, Map.of(), "x".repeat(201)));

    assertThat(missing.status()).isEqualTo(400);
    assertThat(missing.violations().getFirst().parameter()).isEqualTo("Idempotency-Key");
    assertThat(malformed.status()).isEqualTo(400);
    assertThat(tooLong.status()).isEqualTo(400);
  }

  // ---- corpo e limites ---------------------------------------------------------------------

  @Test
  void bodyLimitsAndMalformedJsonAreRefusedWithoutEchoingContent() {
    var integration = threeLevels();
    String items = "{\"produto\":\"A\",\"qtd\":1,\"valor\":1},".repeat(10_001) + "{}";

    ProblemException invalid = failure(integration, "{'numero':");
    ProblemException duplicate =
        failure(integration, "{'numero':'a','numero':'b','clienteId':1,'total':1}");
    ProblemException trailing = failure(integration, "{'numero':'a','clienteId':1,'total':1} {}");
    ProblemException many =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                run(
                    integration,
                    "{\"numero\":\"a\",\"clienteId\":1,\"total\":1,\"itens\":[" + items + "]}"));
    ProblemException array = failure(integration, "[1,2]");
    ProblemException empty =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                engine.execute(
                    SCOPE, integration, new WriteRequest(new byte[0], Map.of(), Map.of(), null)));
    ProblemException huge =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                engine.execute(
                    SCOPE,
                    integration,
                    new WriteRequest(
                        new byte[WriteEngine.DEFAULT_MAX_BODY_BYTES + 1],
                        Map.of(),
                        Map.of(),
                        null)));

    assertThat(invalid.status()).isEqualTo(400);
    assertThat(invalid.type()).endsWith("invalid-json");
    assertThat(duplicate.type()).endsWith("invalid-json");
    assertThat(trailing.type()).endsWith("invalid-json");
    assertThat(many.status()).isEqualTo(400);
    assertThat(many.violations().getFirst().code()).isEqualTo("too_many_items");
    assertThat(array.status()).isEqualTo(400);
    assertThat(empty.type()).endsWith("body-required");
    assertThat(huge.status()).isEqualTo(413);
  }

  @Test
  void theBodySchemaOfTheIntegrationIsEnforced() throws SQLException {
    var integration =
        integration(
            "INSERT",
            "/pedidos",
            "[]",
            j(
                "{'type':'object','required':['numero','clienteId','total'],'additionalProperties':false,"
                    + "'properties':{'numero':{'type':'string','maxLength':30},'clienteId':{'type':'integer','minimum':1},"
                    + "'total':{'type':'number'},'status':{'type':'string'},'itens':{'type':'array'}}}"),
            pedidoRoot(""),
            "");

    ProblemException e = failure(integration, "{'numero':'x','clienteId':0,'extra':true}");

    assertThat(e.status()).isEqualTo(400);
    assertThat(e.violations())
        .extracting(ProblemException.Violation::parameter)
        .contains("/clienteId", "/total", "/extra");
    assertThat(count("dbo.tb_pedido")).isZero();
    assertThat(run(integration, "{\"numero\":\"x\",\"clienteId\":1,\"total\":2}").status())
        .isEqualTo(201);
  }

  @Test
  void numbersKeepTheirPrecisionAndHugeExponentsAreRefused() throws SQLException {
    var integration =
        integration(
            "INSERT",
            "/pedidos",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_pedido','fields':[{'column':'numero','source':'/numero'},"
                    + "{'column':'cliente_id','constant':1},{'column':'vl_total','source':'/total'}]}"),
            "");

    run(integration, "{\"numero\":\"P\",\"total\":12345678901234.56}");
    ProblemException e = failure(integration, "{'numero':'Q','total':1e999}");
    ProblemException overflow = failure(integration, "{'numero':'R','total':1e30}");

    assertThat(scalar("SELECT vl_total FROM dbo.tb_pedido WHERE numero = 'P'"))
        .isEqualTo("12345678901234.56");
    assertThat(e.status()).isEqualTo(400);
    assertThat(overflow.status()).isEqualTo(422);
  }

  @Test
  void aLimitOnRowsStopsHugeTrees() {
    var small =
        new WriteEngine(
            new TenantCatalog(
                new JdbcCatalogIntrospector(registry),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30)),
            new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry)),
            new TransactionTemplate(
                new DataSourceTransactionManager(new TenantRoutingDataSource(registry))),
            idempotency,
            FieldTransformer.NONE,
            3);

    ProblemException e =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                small.execute(
                    SCOPE,
                    threeLevels(),
                    new WriteRequest(
                        pedidoBody(
                                "L",
                                "{\"produto\":\"A\",\"qtd\":1,\"valor\":1},".repeat(3)
                                    + "{\"produto\":\"A\",\"qtd\":1,\"valor\":1}")
                            .getBytes(StandardCharsets.UTF_8),
                        Map.of(),
                        Map.of(),
                        null)));

    assertThat(e.status()).isEqualTo(413);
    assertThat(e.type()).endsWith("too-many-rows");
  }

  // ---- configuração e catálogo -----------------------------------------------------------

  @Test
  void integrationsThatDoNotFitTheDatabaseAreServerSideProblems() {
    var identity =
        integration(
            "INSERT",
            "/x",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_pedido','fields':[{'column':'id','source':'/id'},{'column':'numero','source':'/n'}]}"),
            "");
    var view =
        integration(
            "INSERT",
            "/y",
            "[]",
            null,
            j("{'table':'dbo.vw_pedido','fields':[{'column':'numero','source':'/n'}]}"),
            "");
    var unmapped =
        integration(
            "INSERT",
            "/z",
            "[]",
            null,
            j("{'table':'dbo.tb_pedido','fields':[{'column':'numero','source':'/n'}]}"),
            "");
    var unknown =
        integration(
            "INSERT",
            "/w",
            "[]",
            null,
            j("{'table':'dbo.tb_pedido','fields':[{'column':'nao_existe','source':'/n'}]}"),
            "");

    assertThat(failure(identity, "{'id':1,'n':'a'}").status()).isEqualTo(500);
    assertThat(failure(view, "{'n':'a'}").status()).isEqualTo(500);
    assertThat(failure(unmapped, "{'n':'a'}").status()).isEqualTo(500);
    ProblemException changed = failure(unknown, "{'n':'a'}");
    assertThat(changed.status()).isEqualTo(503);
    assertThat(changed.type()).endsWith("schema-changed");
  }

  @Test
  void transformsNormalizeValuesBeforeTheyReachTheDatabase() throws SQLException {
    var integration =
        integration(
            "INSERT",
            "/t",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_pedido','fields':[{'column':'numero','source':'/n','transforms':[{'fn':'trim'},{'fn':'upper'}]},"
                    + "{'column':'cliente_id','source':'/c','transforms':[{'fn':'digits'}]},"
                    + "{'column':'vl_total','source':'/v','transforms':[{'fn':'toDecimal','args':[2]}]},"
                    + "{'column':'status','source':'/s','transforms':[{'fn':'map','args':[{'A':'ABERTO','P':'PAGO'}]}]},"
                    + "{'column':'obs','source':'/o','transforms':[{'fn':'default','args':['sem obs']}]}]}"),
            "");
    var catalog =
        new TenantCatalog(
            new JdbcCatalogIntrospector(registry), Duration.ofMinutes(5), Duration.ofSeconds(30));
    var jdbc = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
    var withTransforms =
        new WriteEngine(
            catalog,
            jdbc,
            new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource())),
            idempotency,
            new Transforms(),
            50_000);
    byte[] ok =
        "{\"n\":\"  ab-1 \",\"c\":\"C-77\",\"v\":\"10.005\",\"s\":\"P\"}"
            .getBytes(StandardCharsets.UTF_8);
    byte[] bad =
        "{\"n\":\"x\",\"c\":\"1\",\"v\":\"abc\",\"s\":\"Z\"}".getBytes(StandardCharsets.UTF_8);

    withTransforms.execute(SCOPE, integration, new WriteRequest(ok, Map.of(), Map.of(), null));
    ProblemException e =
        catchThrowableOfType(
            ProblemException.class,
            () ->
                withTransforms.execute(
                    SCOPE, integration, new WriteRequest(bad, Map.of(), Map.of(), null)));

    assertThat(
            scalar(
                "SELECT numero + '|' + CAST(cliente_id AS VARCHAR) + '|' + CAST(vl_total AS VARCHAR)"
                    + " + '|' + status + '|' + obs FROM dbo.tb_pedido"))
        .isEqualTo("AB-1|77|10.01|PAGO|sem obs");
    assertThat(e.status()).isEqualTo(400);
    assertThat(e.violations())
        .extracting(ProblemException.Violation::parameter)
        .contains("/v", "/s");
    assertThat(count("dbo.tb_pedido")).isEqualTo(1);
  }

  @Test
  void fieldsWithTransformsAreRefusedUntilTheyAreImplemented() {
    var integration =
        integration(
            "INSERT",
            "/t",
            "[]",
            null,
            j(
                "{'table':'dbo.tb_pedido','fields':[{'column':'numero','source':'/n','transforms':[{'fn':'trim'}]},"
                    + "{'column':'cliente_id','constant':1},{'column':'vl_total','constant':1}]}"),
            "");

    assertThat(failure(integration, "{'n':' a '}").status()).isEqualTo(500);
  }

  @Test
  void aBodyValueOfTheWrongTypeNeverReachesTheDatabaseAndDoesNotEchoTheValue() {
    ProblemException e =
        failure(threeLevels(), "{'numero':'P','clienteId':'valor-secreto','total':1}");

    assertThat(e.violations().getFirst().message()).doesNotContain("valor-secreto");
    Map<String, Integer> seen = new HashMap<>();
    e.violations().forEach(v -> seen.merge(v.code(), 1, Integer::sum));
    assertThat(seen).containsKey("invalid_value");
  }
}
