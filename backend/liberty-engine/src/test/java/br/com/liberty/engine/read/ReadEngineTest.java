package br.com.liberty.engine.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O motor de leitura contra SQL Server real, com a mesma fiação de produção (registro de pools e
 * roteador).
 */
class ReadEngineTest {

  private static final TenantScope SCOPE =
      new TenantScope(new TenantId("leitura"), EnvironmentKind.HML);
  private static final SnapshotService SNAPSHOTS = new SnapshotService();

  private static CoreTestDatabase.Scratch db;
  private static TenantPoolRegistry registry;
  private static NamedParameterJdbcTemplate jdbc;
  private static TransactionTemplate readOnly;
  private static ReadEngine engine;
  private static TenantCatalog catalog;

  private static void exec(String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
    }
  }

  @BeforeAll
  static void setUp() throws SQLException {
    db = CoreTestDatabase.create();
    exec(
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, nr_pedido VARCHAR(30) NOT NULL, cliente_id INT NOT NULL,"
            + " vl_total DECIMAL(18,2) NOT NULL, dt_pedido DATETIME2(3) NOT NULL, status VARCHAR(20) NOT NULL, extra INT NULL)",
        "CREATE TABLE dbo.tb_tipos (id INT PRIMARY KEY, texto NVARCHAR(50) NULL, inteiro BIGINT NULL, decimal4 DECIMAL(18,4) NULL,"
            + " flutuante FLOAT NULL, ativo BIT NULL, data DATE NULL, hora TIME(3) NULL, momento DATETIME2(3) NULL,"
            + " fuso DATETIMEOFFSET(0) NULL, chave UNIQUEIDENTIFIER NULL, bin VARBINARY(8) NULL, xml XML NULL)");
    for (int i = 1; i <= 25; i++) {
      exec(
          "INSERT INTO dbo.tb_pedido (nr_pedido, cliente_id, vl_total, dt_pedido, status) VALUES ('P-"
              + String.format("%04d", i)
              + "', "
              + (i % 3 + 1)
              + ", "
              + i * 10
              + ".50, '2026-01-"
              + String.format("%02d", i)
              + "T10:00:00', '"
              + (i % 2 == 0 ? "PAGO" : "ABERTO")
              + "')");
    }
    exec(
        "INSERT INTO dbo.tb_tipos VALUES (1, N'olá', 9007199254740993, 12.3400, 1.5, 1, '2026-11-06', '10:15:30.123',"
            + " '2026-11-06T10:15:30.123', '2026-11-06T10:15:30-03:00', '123E4567-E89B-12D3-A456-426614174000', 0x0102AB, '<a>1</a>')",
        "INSERT INTO dbo.tb_tipos (id) VALUES (2)");
    registry =
        new TenantPoolRegistry(
            scope -> Optional.of(new TenantConnectionInfo(db.url(), db.user(), "ref/x", 4)),
            reference -> db.password(),
            Duration.ofMinutes(10));
    jdbc = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
    readOnly =
        new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    readOnly.setReadOnly(true);
    catalog =
        new TenantCatalog(
            new JdbcCatalogIntrospector(registry), Duration.ofMinutes(5), Duration.ofSeconds(30));
    engine = new ReadEngine(catalog, jdbc, readOnly);
  }

  @AfterAll
  static void tearDown() {
    registry.destroy();
  }

  private static PublishedIntegration integration(String selectJson, String paramsJson) {
    String json =
        "{\"schemaVersion\":1,\"integration\":{\"key\":\"leitura\",\"name\":\"Leitura\",\"method\":\"GET\",\"route\":\"/x/{id}\"},"
            + "\"request\":{\"params\":"
            + paramsJson
            + "},\"operation\":{\"type\":\"SELECT\",\"select\":"
            + selectJson
            + "}}";
    var parsed = SNAPSHOTS.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new PublishedIntegration(
        1, "leitura", "GET", 1, 1, parsed.model(), RoutePattern.parse("/x/{id}"));
  }

  private static final String PARAMS =
      "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"string\"},{\"name\":\"cliente\",\"in\":\"query\",\"type\":\"integer\"},"
          + "{\"name\":\"status\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"prefixo\",\"in\":\"query\",\"type\":\"string\"},"
          + "{\"name\":\"ids\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"periodo\",\"in\":\"query\",\"type\":\"string\"},"
          + "{\"name\":\"minimo\",\"in\":\"query\",\"type\":\"number\"}]";

  private static final String LIST =
      "{\"table\":\"dbo.tb_pedido\",\"columns\":[\"id\",\"nr_pedido\",\"vl_total\",\"dt_pedido\",\"status\"],"
          + "\"filters\":[{\"param\":\"cliente\",\"column\":\"cliente_id\",\"operator\":\"=\"},{\"param\":\"status\",\"column\":\"status\",\"operator\":\"<>\"},"
          + "{\"param\":\"prefixo\",\"column\":\"nr_pedido\",\"operator\":\"LIKE_PREFIX\"},{\"param\":\"ids\",\"column\":\"id\",\"operator\":\"IN\"},"
          + "{\"param\":\"periodo\",\"column\":\"dt_pedido\",\"operator\":\"BETWEEN\"},{\"param\":\"minimo\",\"column\":\"vl_total\",\"operator\":\">=\"}],"
          + "\"sortable\":[\"id\",\"vl_total\"],\"defaultSort\":{\"column\":\"id\",\"direction\":\"DESC\"},\"pageSize\":{\"default\":10,\"max\":20}}";

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(ReadResult result) {
    return (List<Map<String, Object>>) ((Map<String, Object>) result.body()).get("items");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> page(ReadResult result) {
    return (Map<String, Object>) ((Map<String, Object>) result.body()).get("page");
  }

  private ReadResult list(Map<String, List<String>> query) {
    return engine.execute(SCOPE, integration(LIST, PARAMS), Map.of("id", "1"), query);
  }

  private static Map<String, List<String>> q(String... pairs) {
    Map<String, List<String>> map = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], List.of(pairs[i + 1]));
    }
    return map;
  }

  @Test
  void pagesInDescendingIdOrderAndReportsWhetherThereIsMore() {
    ReadResult first = list(q());
    ReadResult last = list(q("offset", "20", "limit", "10"));
    ReadResult beyond = list(q("offset", "50"));

    assertThat(items(first)).hasSize(10);
    assertThat(items(first).getFirst().get("id")).isEqualTo(25L);
    assertThat(page(first))
        .containsEntry("limit", 10)
        .containsEntry("offset", 0)
        .containsEntry("returned", 10)
        .containsEntry("hasMore", true);
    assertThat(items(last)).hasSize(5);
    assertThat(page(last)).containsEntry("hasMore", false).containsEntry("returned", 5);
    assertThat(items(beyond)).isEmpty();
    assertThat(page(beyond)).containsEntry("hasMore", false);
  }

  @Test
  void pagesNeverRepeatOrSkipRows() {
    java.util.Set<Object> seen = new java.util.HashSet<>();
    for (int offset = 0; offset < 25; offset += 7) {
      items(list(q("offset", String.valueOf(offset), "limit", "7")))
          .forEach(row -> assertThat(seen.add(row.get("id"))).isTrue());
    }

    assertThat(seen).hasSize(25);
  }

  @Test
  void sortsOnAllowedColumnsOnly() {
    assertThat(
            items(list(q("sort", "vl_total", "limit", "3"))).stream()
                .map(r -> r.get("id"))
                .toList())
        .containsExactly(1L, 2L, 3L);
    assertThat(
            items(list(q("sort", "-vl_total", "limit", "2"))).stream()
                .map(r -> r.get("id"))
                .toList())
        .containsExactly(25L, 24L);
    assertThatThrownBy(() -> list(q("sort", "status")))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  void appliesEveryFilterOperatorAgainstTheRealDatabase() {
    assertThat(items(list(q("cliente", "2", "limit", "20"))).stream().allMatch(r -> true)).isTrue();
    assertThat(page(list(q("cliente", "2", "limit", "20"))).get("returned")).isEqualTo(9);
    assertThat(
            items(list(q("status", "PAGO", "limit", "20"))).stream()
                .map(r -> r.get("status"))
                .distinct()
                .toList())
        .containsExactly("ABERTO");
    assertThat(
            items(list(q("prefixo", "P-001", "limit", "20"))).stream()
                .map(r -> r.get("nr_pedido"))
                .toList())
        .hasSize(10)
        .allMatch(n -> ((String) n).startsWith("P-001"));
    assertThat(
            items(list(q("ids", "3, 5,7", "sort", "id"))).stream().map(r -> r.get("id")).toList())
        .containsExactly(3L, 5L, 7L);
    assertThat(page(list(q("periodo", "2026-01-05,2026-01-07", "limit", "20"))).get("returned"))
        .isEqualTo(3);
    assertThat(
            items(list(q("minimo", "245", "limit", "20"))).stream().map(r -> r.get("id")).toList())
        .containsExactly(25L);
    assertThat(
            page(list(q("cliente", "1", "status", "ABERTO", "minimo", "100", "limit", "20")))
                .get("returned"))
        .as("cliente 1, status <> ABERTO (pares 6, 12, 18, 24) e total >= 100")
        .isEqualTo(3);
  }

  @Test
  void likePrefixTreatsWildcardsAsLiteralTextAndInjectionAsJustText() throws SQLException {
    assertThat(items(list(q("prefixo", "%"))).isEmpty()).isTrue();
    assertThat(items(list(q("prefixo", "P-00_1"))).isEmpty()).isTrue();
    assertThat(items(list(q("prefixo", "' OR 1=1 --"))).isEmpty()).isTrue();
    assertThat(items(list(q("status", "x'; DROP TABLE dbo.tb_pedido;--"))).size()).isEqualTo(10);
    assertThat(page(list(q("limit", "20", "offset", "10"))).get("returned")).isEqualTo(15);
  }

  @Test
  void mapsEveryColumnTypeToItsJsonValue() {
    String select =
        "{\"table\":\"dbo.tb_tipos\",\"columns\":[\"id\",\"texto\",\"inteiro\",\"decimal4\",\"flutuante\",\"ativo\",\"data\",\"hora\",\"momento\",\"fuso\",\"chave\",\"bin\",\"xml\"],"
            + "\"filters\":[{\"param\":\"id\",\"column\":\"id\",\"operator\":\"=\",\"required\":true}],\"single\":true}";

    Map<String, Object> row =
        (Map<String, Object>)
            engine
                .execute(
                    SCOPE,
                    integration(select, "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"integer\"}]"),
                    Map.of("id", "1"),
                    Map.of())
                .body();

    assertThat(row.get("id")).isEqualTo(1L);
    assertThat(row.get("texto")).isEqualTo("olá");
    assertThat(row.get("inteiro")).isEqualTo(9007199254740993L);
    assertThat(row.get("decimal4")).isEqualTo(new BigDecimal("12.3400"));
    assertThat(row.get("flutuante")).isEqualTo(1.5d);
    assertThat(row.get("ativo")).isEqualTo(true);
    assertThat(row.get("data")).isEqualTo("2026-11-06");
    assertThat(row.get("hora")).isEqualTo("10:15:30.123");
    assertThat(row.get("momento")).isEqualTo("2026-11-06T10:15:30.123");
    assertThat(row.get("fuso")).isEqualTo("2026-11-06T10:15:30-03:00");
    assertThat(row.get("chave")).isEqualTo("123E4567-E89B-12D3-A456-426614174000");
    assertThat(row.get("bin")).isEqualTo("AQKr");
    assertThat((String) row.get("xml")).contains("<a>1</a>");
    assertThat(row.keySet())
        .containsExactly(
            "id",
            "texto",
            "inteiro",
            "decimal4",
            "flutuante",
            "ativo",
            "data",
            "hora",
            "momento",
            "fuso",
            "chave",
            "bin",
            "xml");
  }

  @Test
  void nullsComeBackAsNull() {
    String select =
        "{\"table\":\"dbo.tb_tipos\",\"columns\":[\"id\",\"texto\",\"inteiro\",\"decimal4\",\"ativo\",\"data\",\"momento\",\"chave\",\"bin\"],"
            + "\"filters\":[{\"param\":\"id\",\"column\":\"id\",\"operator\":\"=\",\"required\":true}],\"single\":true}";

    Map<String, Object> row =
        (Map<String, Object>)
            engine
                .execute(
                    SCOPE,
                    integration(select, "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"integer\"}]"),
                    Map.of("id", "2"),
                    Map.of())
                .body();

    assertThat(row.get("id")).isEqualTo(2L);
    row.entrySet().stream()
        .filter(e -> !e.getKey().equals("id"))
        .forEach(e -> assertThat(e.getValue()).as(e.getKey()).isNull());
  }

  @Test
  void singleReturnsTheRowOr404Or409() {
    String select =
        "{\"table\":\"dbo.tb_pedido\",\"columns\":[\"id\",\"nr_pedido\"],\"filters\":[{\"param\":\"numero\",\"column\":\"nr_pedido\",\"operator\":\"=\",\"required\":true},"
            + "{\"param\":\"cliente\",\"column\":\"cliente_id\",\"operator\":\"=\"}],\"single\":true}";
    String params =
        "[{\"name\":\"numero\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"cliente\",\"in\":\"query\",\"type\":\"integer\"},{\"name\":\"id\",\"in\":\"path\",\"type\":\"string\"}]";
    PublishedIntegration single = integration(select, params);
    Map<String, Object> found =
        (Map<String, Object>)
            engine.execute(SCOPE, single, Map.of("id", "1"), q("numero", "P-0003")).body();

    assertThat(found).containsEntry("id", 3L).containsEntry("nr_pedido", "P-0003");
    assertThatThrownBy(
            () -> engine.execute(SCOPE, single, Map.of("id", "1"), q("numero", "NAO-EXISTE")))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(404));
    PublishedIntegration ambiguous =
        integration(
            select.replace(
                "\"required\":true},{\"param\":\"cliente\"",
                "\"required\":false},{\"param\":\"cliente\""),
            params.replace(
                "{\"name\":\"numero\",\"in\":\"query\",\"type\":\"string\"}",
                "{\"name\":\"numero\",\"in\":\"query\",\"type\":\"string\"}"));
    assertThatThrownBy(() -> engine.execute(SCOPE, ambiguous, Map.of("id", "1"), q("cliente", "2")))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(409));
  }

  @Test
  void anUnreachableTenantDatabaseIs503() {
    var down =
        new TenantPoolRegistry(scope -> Optional.empty(), reference -> "x", Duration.ofMinutes(1));
    var template = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(down));
    var tx =
        new TransactionTemplate(
            new DataSourceTransactionManager(template.getJdbcTemplate().getDataSource()));
    var isolated =
        new ReadEngine(
            new TenantCatalog(
                new JdbcCatalogIntrospector(down), Duration.ofMinutes(1), Duration.ofSeconds(30)),
            template,
            tx);

    assertThatThrownBy(
            () -> isolated.execute(SCOPE, integration(LIST, PARAMS), Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.status()).isEqualTo(503);
              assertThat(e.type()).endsWith("tenant-unavailable");
            });
    down.destroy();
  }

  @Test
  void aSnapshotNamingAMissingTableOrColumnIs503SchemaChanged() throws SQLException {
    PublishedIntegration missingTable =
        integration(
            "{\"table\":\"dbo.nao_existe\",\"columns\":[\"id\"]}",
            "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"string\"}]");

    assertThatThrownBy(() -> engine.execute(SCOPE, missingTable, Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.status()).isEqualTo(503);
              assertThat(e.type()).endsWith("schema-changed");
            });
  }

  @Test
  void aColumnDroppedAfterCachingHealsTheCatalogAndAnswers503() throws SQLException {
    exec(
        "CREATE TABLE dbo.efemera (id INT PRIMARY KEY, a INT, b INT)",
        "INSERT INTO dbo.efemera VALUES (1, 1, 1)");
    PublishedIntegration integration =
        integration(
            "{\"table\":\"dbo.efemera\",\"columns\":[\"id\",\"a\",\"b\"]}",
            "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"string\"}]");
    assertThat(items(engine.execute(SCOPE, integration, Map.of("id", "1"), q()))).hasSize(1);

    exec("ALTER TABLE dbo.efemera DROP COLUMN b");

    assertThatThrownBy(() -> engine.execute(SCOPE, integration, Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(
            ProblemException.class, e -> assertThat(e.type()).endsWith("schema-changed"));
    assertThatThrownBy(() -> engine.execute(SCOPE, integration, Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(503));
  }

  @Test
  void timeoutsAndUnexpectedDatabaseErrorsBecomeGenericProblems() {
    var timingOut =
        new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()) {
          @Override
          public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> rowMapper) {
            throw new QueryTimeoutException("estourou");
          }
        };
    var failing =
        new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()) {
          @Override
          public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> rowMapper) {
            throw new org.springframework.jdbc.UncategorizedSQLException(
                "x", "SELECT secreto", new SQLException("senha=abc", "S0001", 4060));
          }
        };

    assertThatThrownBy(
            () ->
                new ReadEngine(catalog, timingOut, readOnly)
                    .execute(SCOPE, integration(LIST, PARAMS), Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(504));
    assertThatThrownBy(
            () ->
                new ReadEngine(catalog, failing, readOnly)
                    .execute(SCOPE, integration(LIST, PARAMS), Map.of("id", "1"), q()))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.status()).isEqualTo(500);
              assertThat(e.getMessage()).doesNotContain("senha").doesNotContain("secreto");
            });
  }

  @Test
  void misconfiguredFilterTypesAreInternalErrors() {
    PublishedIntegration binaryFilter =
        integration(
            "{\"table\":\"dbo.tb_tipos\",\"columns\":[\"id\"],\"filters\":[{\"param\":\"x\",\"column\":\"bin\",\"operator\":\"=\"}]}",
            "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"string\"},{\"name\":\"x\",\"in\":\"query\",\"type\":\"string\"}]");

    assertThatThrownBy(() -> engine.execute(SCOPE, binaryFilter, Map.of("id", "1"), q("x", "1")))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(500));
  }
}
