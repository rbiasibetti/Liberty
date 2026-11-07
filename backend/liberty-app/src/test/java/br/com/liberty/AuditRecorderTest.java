package br.com.liberty;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.engine.audit.AuditEvent;
import br.com.liberty.engine.audit.AuditRecorder;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A gravação assíncrona da auditoria contra o banco central real. */
class AuditRecorderTest {

  private static CoreTestDatabase.Scratch db;
  private static DataSource dataSource;

  @BeforeAll
  static void migrate() throws SQLException {
    db = CoreTestDatabase.create();
    Flyway.configure()
        .dataSource(db.url(), db.user(), db.password())
        .locations("classpath:db/migration/core")
        .table("int_flyway_schema_history")
        .load()
        .migrate();
    dataSource = new DriverManagerDataSource(db.url(), db.user(), db.password());
    sql("INSERT INTO int_tenant (slug, name, plan_code) VALUES ('acme', 'Acme', 'STANDARD')");
  }

  @AfterAll
  static void stop() {
    CoreTestDatabase.dropAll();
  }

  private static void sql(String statement) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      st.execute(statement);
    }
  }

  private static String scalar(String statement) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static AuditEvent event(String tenant, int status, String message) {
    return new AuditEvent(
        Instant.now(),
        tenant,
        "pedidos",
        1L,
        2L,
        "HML",
        "GET",
        "/pedidos/{n}",
        status,
        12,
        5L,
        null,
        status >= 400 ? "validation" : null,
        message,
        "req-" + System.nanoTime(),
        "cliente-1");
  }

  private static void eventually(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long limit = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < limit && !condition.getAsBoolean()) {
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Test
  void eventsAreWrittenInBatchesWithMaskedMessagesAndUnknownTenantsAreCounted() throws Exception {
    try (var recorder =
        new AuditRecorder(
            new JdbcTemplate(dataSource),
            new SimpleMeterRegistry(),
            1000,
            100,
            Duration.ofMillis(50))) {
      for (int i = 0; i < 250; i++) {
        recorder.record(event("acme", 200, null));
      }
      recorder.record(event("acme", 400, "e-mail ana@exemplo.com CPF 123.456.789-09 inválido"));
      recorder.record(event("fantasma", 200, null));

      eventually(() -> recorder.written() >= 251 && recorder.failed() >= 1);

      assertThat(recorder.dropped()).isZero();
      assertThat(
              scalar("SELECT COUNT(*) FROM int_audit_execution WHERE integration_key = 'pedidos'"))
          .isEqualTo("251");
      String message =
          scalar("SELECT error_message FROM int_audit_execution WHERE status_code = 400");
      assertThat(message).doesNotContain("ana@", "123.456").contains("***");
      assertThat(scalar("SELECT error_code FROM int_audit_execution WHERE status_code = 400"))
          .isEqualTo("validation");
      assertThat(
              scalar(
                  "SELECT COUNT(*) FROM int_audit_execution e JOIN int_tenant t ON t.id = e.tenant_id"
                      + " WHERE t.slug = 'acme'"))
          .isEqualTo("251");
    }
  }

  @Test
  void aSlowDatabaseNeverSlowsProducersAndEveryDiscardIsCounted() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    // um "banco" que trava a gravação: simula o banco central parado sob carga
    var blocked =
        new JdbcTemplate(dataSource) {
          @Override
          public int[] batchUpdate(
              String sql, org.springframework.jdbc.core.BatchPreparedStatementSetter pss) {
            try {
              release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return super.batchUpdate(sql, pss);
          }
        };
    int capacity = 500;
    int total = 200_000;
    try (var recorder =
        new AuditRecorder(
            blocked, new SimpleMeterRegistry(), capacity, 100, Duration.ofMillis(20))) {
      long worst = 0;
      long start = System.nanoTime();
      int accepted = 0;
      for (int i = 0; i < total; i++) {
        long t0 = System.nanoTime();
        if (recorder.record(event("acme", 200, null))) {
          accepted++;
        }
        worst = Math.max(worst, System.nanoTime() - t0);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertThat(elapsedMs).as("200 mil eventos entregues sem esperar o banco").isLessThan(5_000);
      assertThat(worst / 1_000_000).as("pior chamada de record").isLessThan(500);
      assertThat(recorder.dropped()).isEqualTo(total - accepted);
      assertThat(recorder.dropped())
          .as("a fila cheia descartou e mediu")
          .isGreaterThan(total - capacity - 200);
      release.countDown();
      long expected = accepted;
      eventually(() -> recorder.written() == expected);
      assertThat(recorder.failed()).isZero();
    }
  }
}
