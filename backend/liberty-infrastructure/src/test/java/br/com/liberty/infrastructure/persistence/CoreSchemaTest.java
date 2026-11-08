package br.com.liberty.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Migração V1 do zero e as restrições que o banco impõe sozinho, sem passar pela aplicação. */
class CoreSchemaTest {

  private static final Set<String> TABLES =
      Set.of(
          "int_tenant",
          "int_tenant_connection",
          "int_user_role",
          "int_api_client",
          "int_integration",
          "int_integration_version",
          "int_publication",
          "int_idempotency",
          "int_audit_execution",
          "int_audit_payload",
          "int_export_job",
          "int_tenant_http_host",
          "int_queue_message");

  private static final int CHECK_OR_FK = 547;
  private static final int UNIQUE_CONSTRAINT = 2627;
  private static final int UNIQUE_INDEX = 2601;

  private static CoreTestDatabase.Scratch db;
  private static Connection connection;
  private static int counter;

  @BeforeAll
  static void migrate() throws SQLException {
    db = CoreTestDatabase.create();
    Flyway flyway = flyway();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
    connection = DriverManager.getConnection(db.url(), db.user(), db.password());
  }

  @AfterAll
  static void close() throws SQLException {
    connection.close();
  }

  private static Flyway flyway() {
    return Flyway.configure()
        .dataSource(db.url(), db.user(), db.password())
        .locations("classpath:db/migration/core")
        .table("int_flyway_schema_history")
        .load();
  }

  private static void sql(String statement, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(statement)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.execute();
    }
  }

  private static long scalar(String statement, Object... args) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(statement)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long tenant() throws SQLException {
    String slug = "t" + (++counter) + "-x";
    sql("INSERT INTO int_tenant (slug, name) VALUES (?, N'Tenant')", slug);
    return scalar("SELECT id FROM int_tenant WHERE slug = ?", slug);
  }

  private static long integration(long tenantId, String key, String method, String route)
      throws SQLException {
    sql(
        "INSERT INTO int_integration (tenant_id, integration_key, name, http_method, route_pattern)"
            + " VALUES (?, ?, N'I', ?, ?)",
        tenantId,
        key,
        method,
        route);
    return scalar(
        "SELECT id FROM int_integration WHERE tenant_id = ? AND integration_key = ?",
        tenantId,
        key);
  }

  private static long version(
      long tenantId, long integrationId, int no, String state, String author) throws SQLException {
    sql(
        "INSERT INTO int_integration_version (tenant_id, integration_id, version_no, state, snapshot,"
            + " snapshot_sha256, author_sub) VALUES (?, ?, ?, ?, N'{\"a\":1}', 0x"
            + "00".repeat(32)
            + ", ?)",
        tenantId,
        integrationId,
        no,
        state,
        author);
    return scalar(
        "SELECT id FROM int_integration_version WHERE integration_id = ? AND version_no = ?",
        integrationId,
        no);
  }

  private static void assertSqlError(ThrowingRunnable action, int... codes) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            SQLException.class,
            e ->
                assertThat(e.getErrorCode())
                    .as(e.getMessage())
                    .isIn(java.util.Arrays.stream(codes).boxed().toList()));
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws SQLException;
  }

  @Test
  void createsExactlyThePlatformTables() throws SQLException {
    Set<String> found = new HashSet<>();
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("SELECT name FROM sys.tables WHERE name LIKE 'int[_]%'")) {
      while (rs.next()) {
        found.add(rs.getString(1));
      }
    }

    found.remove("int_flyway_schema_history");
    assertThat(found).containsExactlyInAnyOrderElementsOf(TABLES);
  }

  @Test
  void reservedSlugsCannotBeTenants() {
    for (String reserved : new String[] {"api", "oauth2", "actuator", "platform"}) {
      assertSqlError(
          () -> sql("INSERT INTO int_tenant (slug, name) VALUES (?, N'x')", reserved), CHECK_OR_FK);
    }
  }

  @Test
  void migrationIsIdempotent() {
    assertThat(flyway().migrate().migrationsExecuted).isZero();
  }

  @Test
  void noForeignKeyCascades() throws SQLException {
    assertThat(scalar("SELECT COUNT(*) FROM sys.foreign_keys WHERE delete_referential_action <> 0"))
        .isZero();
  }

  @Test
  void queueMessagesCarryTenantEnvironmentAndValidJson() throws SQLException {
    long tenant = tenant();
    String insert =
        "INSERT INTO int_queue_message (tenant_id, environment, queue_name, payload, status)"
            + " VALUES (?, ?, ?, ?, ?)";
    sql(insert, tenant, "HML", "webhook", "{\"a\":1}", "READY");
    assertSqlError(() -> sql(insert, tenant, "XXX", "webhook", "{}", "READY"), CHECK_OR_FK);
    assertSqlError(() -> sql(insert, tenant, "HML", "Webhook", "{}", "READY"), CHECK_OR_FK);
    assertSqlError(
        () -> sql(insert, tenant, "HML", "webhook", "isto não é json", "READY"), CHECK_OR_FK);
    assertSqlError(() -> sql(insert, tenant, "HML", "webhook", "{}", "DONE"), CHECK_OR_FK);
    assertSqlError(() -> sql(insert, 999_999L, "HML", "webhook", "{}", "READY"), CHECK_OR_FK);
  }

  @Test
  void outboundHostsAreLowercaseDnsNamesNeverIpLiterals() throws SQLException {
    long tenant = tenant();
    String insert =
        "INSERT INTO int_tenant_http_host (tenant_id, host, created_by) VALUES (?, ?, 'admin')";
    for (String bad :
        new String[] {
          "API.exemplo.com", "127.0.0.1", "10.1.2.3:443", "a b.com", ".x.com", "x.com."
        }) {
      assertSqlError(() -> sql(insert, tenant, bad), CHECK_OR_FK);
    }
    sql(insert, tenant, "api.exemplo.com");
    sql(insert, tenant, "pagamentos.exemplo.com:8443");
    assertSqlError(() -> sql(insert, tenant, "api.exemplo.com"), UNIQUE_CONSTRAINT);
    assertSqlError(() -> sql(insert, 999_999L, "outro.exemplo.com"), CHECK_OR_FK);
  }

  @Test
  void tenantSlugFollowsTheTenantIdFormat() throws SQLException {
    for (String bad : new String[] {"a", "-ab", "Cliente", "cli_ente", "cli ente", "a/b"}) {
      assertSqlError(
          () -> sql("INSERT INTO int_tenant (slug, name) VALUES (?, N'x')", bad), CHECK_OR_FK);
    }
    sql("INSERT INTO int_tenant (slug, name) VALUES ('ok-1', N'x')");
  }

  @Test
  void routesAndKeysAreUniquePerTenant() throws SQLException {
    long tenant = tenant();
    integration(tenant, "pedido", "POST", "/pedidos");

    assertSqlError(() -> integration(tenant, "pedido", "PUT", "/outra"), UNIQUE_CONSTRAINT);
    assertSqlError(() -> integration(tenant, "outro", "POST", "/pedidos"), UNIQUE_CONSTRAINT);
    integration(tenant(), "pedido", "POST", "/pedidos"); // outro tenant pode repetir
  }

  @Test
  void oneDraftPerIntegration() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "rascunho", "POST", "/r");
    version(tenant, integration, 1, "DRAFT", "ana");

    assertSqlError(() -> version(tenant, integration, 2, "DRAFT", "ana"), UNIQUE_INDEX);
    assertSqlError(() -> version(tenant, integration, 1, "IN_REVIEW", "ana"), UNIQUE_CONSTRAINT);
  }

  @Test
  void reviewerCannotBeTheAuthor() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "revisor", "POST", "/rv");
    long id = version(tenant, integration, 1, "IN_REVIEW", "ana");

    assertSqlError(
        () -> sql("UPDATE int_integration_version SET reviewer_sub = 'ana' WHERE id = ?", id),
        CHECK_OR_FK);
  }

  @Test
  void snapshotMustBeJson() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "json", "POST", "/j");

    assertSqlError(
        () ->
            sql(
                "INSERT INTO int_integration_version (tenant_id, integration_id, version_no, state, snapshot,"
                    + " snapshot_sha256, author_sub) VALUES (?, ?, 1, 'DRAFT', N'nao e json', 0x"
                    + "00".repeat(32)
                    + ", 'ana')",
                tenant,
                integration),
        CHECK_OR_FK);
  }

  @Test
  void versionsFreezeAfterLeavingDraft() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "congela", "POST", "/c");
    long id = version(tenant, integration, 1, "DRAFT", "ana");

    sql(
        "UPDATE int_integration_version SET snapshot = N'{\"a\":2}' WHERE id = ?",
        id); // rascunho edita
    sql("UPDATE int_integration_version SET state = 'IN_REVIEW' WHERE id = ?", id);

    assertSqlError(
        () -> sql("UPDATE int_integration_version SET snapshot = N'{\"a\":3}' WHERE id = ?", id),
        50002);
    assertSqlError(() -> sql("DELETE FROM int_integration_version WHERE id = ?", id), 50001);
    assertSqlError(
        () -> sql("UPDATE int_integration_version SET state = 'DRAFT' WHERE id = ?", id), 50003);

    sql(
        "UPDATE int_integration_version SET state = 'PUBLISHED', reviewer_sub = 'bia' WHERE id = ?",
        id);
    assertSqlError(
        () -> sql("UPDATE int_integration_version SET state = 'REJECTED' WHERE id = ?", id), 50003);
  }

  @Test
  void draftCannotSkipReview() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "atalho", "POST", "/a");
    long id = version(tenant, integration, 1, "DRAFT", "ana");

    assertSqlError(
        () -> sql("UPDATE int_integration_version SET state = 'PUBLISHED' WHERE id = ?", id),
        50003);
  }

  @Test
  void draftsCanBeDeleted() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "descarte", "POST", "/d");
    long id = version(tenant, integration, 1, "DRAFT", "ana");

    sql("DELETE FROM int_integration_version WHERE id = ?", id);

    assertThat(scalar("SELECT COUNT(*) FROM int_integration_version WHERE id = ?", id)).isZero();
  }

  @Test
  void onlyOneActivePublicationPerIntegrationAndEnvironment() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "publica", "POST", "/p");
    long version = version(tenant, integration, 1, "IN_REVIEW", "ana");
    String insert =
        "INSERT INTO int_publication (tenant_id, integration_id, version_id, environment, is_active, published_by)"
            + " VALUES (?, ?, ?, ?, ?, 'bia')";
    sql(insert, tenant, integration, version, "HML", true);

    assertSqlError(() -> sql(insert, tenant, integration, version, "HML", true), UNIQUE_INDEX);
    sql(insert, tenant, integration, version, "PRD", true);
    sql(insert, tenant, integration, version, "HML", false);
  }

  @Test
  void idempotencyKeyIsUniquePerTenantAndIntegration() throws SQLException {
    long tenant = tenant();
    long integration = integration(tenant, "idem", "POST", "/i");
    String insert =
        "INSERT INTO int_idempotency (tenant_id, integration_id, idem_key, request_sha256, status, expires_at)"
            + " VALUES (?, ?, 'k1', 0x"
            + "00".repeat(32)
            + ", 'IN_PROGRESS', DATEADD(day, 1, SYSUTCDATETIME()))";
    sql(insert, tenant, integration);

    assertSqlError(() -> sql(insert, tenant, integration), UNIQUE_CONSTRAINT);
  }

  @Test
  void auditTablesArePartitionedByMonthAndHaveNoForeignKeys() throws SQLException {
    // sem FK o histórico de auditoria pode trocar partições (V3); a auditoria é partida por mês
    sql(
        "INSERT INTO int_audit_payload (created_at, execution_id, request_payload)"
            + " VALUES (SYSUTCDATETIME(), 999999, 0x01)");

    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM sys.partitions WHERE object_id = OBJECT_ID('int_audit_execution')"
                    + " AND index_id = 1")) {
      rs.next();
      assertThat(rs.getInt(1)).isGreaterThan(30);
    }
  }

  @Test
  void connectionsStorePoolBoundsButNeverAPassword() throws SQLException {
    long tenant = tenant();
    String insert =
        "INSERT INTO int_tenant_connection (tenant_id, environment, jdbc_url, db_username, secret_ref, pool_max)"
            + " VALUES (?, ?, 'jdbc:sqlserver://h', 'u', 'secret/x', ?)";

    assertSqlError(() -> sql(insert, tenant, "HML", 0), CHECK_OR_FK);
    assertSqlError(() -> sql(insert, tenant, "DEV", 5), CHECK_OR_FK);
    sql(insert, tenant, "HML", 5);
    assertSqlError(() -> sql(insert, tenant, "HML", 5), UNIQUE_CONSTRAINT);
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('int_tenant_connection')"
                    + " AND name LIKE '%password%'"))
        .isZero();
  }
}
