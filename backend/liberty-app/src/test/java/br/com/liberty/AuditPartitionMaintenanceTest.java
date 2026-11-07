package br.com.liberty;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Partições mensais da auditoria: manutenção, arquivamento por troca de partição e idempotência.
 */
class AuditPartitionMaintenanceTest {

  private static CoreTestDatabase.Scratch db;

  @BeforeAll
  static void migrate() {
    db = CoreTestDatabase.create();
    Flyway.configure()
        .dataSource(db.url(), db.user(), db.password())
        .locations("classpath:db/migration/core")
        .table("int_flyway_schema_history")
        .load()
        .migrate();
  }

  @AfterAll
  static void stop() {
    CoreTestDatabase.dropAll();
  }

  private static String scalar(String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
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

  private static void insert(java.time.LocalDateTime createdAt, String request)
      throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO int_audit_execution (created_at, tenant_id, integration_key, environment,"
                    + " http_method, route, status_code, duration_ms, request_id)"
                    + " VALUES (?, 1, 'k', 'HML', 'GET', '/x', 200, 5, ?)")) {
      ps.setObject(1, createdAt);
      ps.setString(2, request);
      ps.executeUpdate();
    }
  }

  @Test
  void maintenanceCreatesFuturePartitionsAndArchivesOldOnesWithoutLosingRows() throws SQLException {
    insert(java.time.LocalDateTime.of(2020, 3, 10, 10, 0), "velha-1");
    insert(java.time.LocalDateTime.of(2020, 3, 11, 10, 0), "velha-2");
    insert(java.time.LocalDateTime.of(2020, 4, 1, 0, 0), "velha-3");
    insert(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), "atual-1");
    String before =
        scalar(
            "SELECT COUNT(*) FROM sys.partition_range_values v JOIN sys.partition_functions f"
                + " ON f.function_id = v.function_id WHERE f.name = 'pf_audit_month'");

    exec("EXEC dbo.sp_int_audit_maintain @monthsAhead = 6, @retentionMonths = 13");

    assertThat(scalar("SELECT COUNT(*) FROM int_audit_execution"))
        .as("só a linha atual fica")
        .isEqualTo("1");
    assertThat(scalar("SELECT request_id FROM int_audit_execution")).isEqualTo("atual-1");
    String archived =
        scalar(
            "SELECT SUM(p.rows) FROM sys.partitions p JOIN sys.tables t ON t.object_id = p.object_id"
                + " WHERE t.name LIKE 'int_audit_archive_%' AND p.index_id = 1");
    assertThat(archived).as("as 3 linhas antigas foram para tabelas de arquivo").isEqualTo("3");
    assertThat(
            scalar(
                "SELECT COUNT(*) FROM sys.partition_range_values v JOIN sys.partition_functions f"
                    + " ON f.function_id = v.function_id WHERE f.name = 'pf_audit_month'"))
        .as("partições antigas fundidas e futuras garantidas")
        .isNotEqualTo(before);

    exec("EXEC dbo.sp_int_audit_maintain @monthsAhead = 6, @retentionMonths = 13");
    assertThat(scalar("SELECT COUNT(*) FROM int_audit_execution")).as("idempotente").isEqualTo("1");
    // novas linhas continuam entrando normalmente
    insert(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC), "atual-2");
    assertThat(scalar("SELECT COUNT(*) FROM int_audit_execution")).isEqualTo("2");
  }
}
