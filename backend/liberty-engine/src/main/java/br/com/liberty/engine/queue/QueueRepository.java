package br.com.liberty.engine.queue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Acesso à tabela {@code int_queue_message} do banco central. */
public class QueueRepository {

  /** Mensagem reivindicada por um consumidor. */
  record Claimed(
      long id,
      long tenantId,
      String environment,
      String queue,
      int attempts,
      String correlationId,
      Instant createdAt,
      String payload) {}

  /** Contagem por fila e estado. */
  public record Stat(String queue, String status, long count, Instant oldest) {}

  /** Mensagem morta, sem o conteúdo. */
  public record Dead(
      long id,
      String queue,
      String environment,
      int attempts,
      String lastError,
      Instant createdAt,
      Instant diedAt) {}

  private final JdbcTemplate jdbc;

  public QueueRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Slug do tenant, ou nulo se não existir. */
  String slug(long tenantId) {
    var found =
        jdbc.queryForList("SELECT slug FROM int_tenant WHERE id = ?", String.class, tenantId);
    return found.isEmpty() ? null : found.getFirst();
  }

  /** Id do tenant, ou nulo se não existir. */
  Long tenantId(String slug) {
    var found = jdbc.queryForList("SELECT id FROM int_tenant WHERE slug = ?", Long.class, slug);
    return found.isEmpty() ? null : found.getFirst();
  }

  long pending(long tenantId) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT_BIG(*) FROM int_queue_message WHERE tenant_id = ?"
                + " AND status IN ('READY', 'PROCESSING')",
            Long.class,
            tenantId);
    return count == null ? 0 : count;
  }

  void enqueue(
      long tenantId, String environment, String queue, String payloadJson, String correlationId) {
    jdbc.update(
        "INSERT INTO int_queue_message (tenant_id, environment, queue_name, payload, correlation_id)"
            + " VALUES (?, ?, ?, ?, ?)",
        tenantId,
        environment,
        queue,
        payloadJson,
        correlationId);
  }

  /** Tenants com mensagem pronta, a mais antiga primeiro (ninguém fica para trás). */
  List<Long> tenantsWithReady(int limit) {
    return jdbc.queryForList(
        "SELECT TOP (?) tenant_id FROM int_queue_message WHERE status = 'READY'"
            + " AND next_attempt_at <= SYSUTCDATETIME() GROUP BY tenant_id"
            + " ORDER BY MIN(next_attempt_at)",
        Long.class,
        limit);
  }

  /**
   * Reivindica até {@code limit} mensagens do tenant; READPAST ignora as que outro consumidor já
   * pegou.
   */
  List<Claimed> claim(long tenantId, int limit, int leaseSeconds) {
    return jdbc.query(
        "WITH c AS (SELECT TOP (?) * FROM int_queue_message WITH (ROWLOCK, UPDLOCK, READPAST)"
            + " WHERE tenant_id = ? AND status = 'READY' AND next_attempt_at <= SYSUTCDATETIME()"
            + " ORDER BY next_attempt_at, id)"
            + " UPDATE c SET status = 'PROCESSING', attempts = attempts + 1,"
            + " locked_until = DATEADD(second, ?, SYSUTCDATETIME())"
            + " OUTPUT inserted.id, inserted.tenant_id, inserted.environment, inserted.queue_name,"
            + " inserted.attempts, inserted.correlation_id, inserted.created_at, inserted.payload",
        (rs, i) ->
            new Claimed(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getInt(5),
                rs.getString(6),
                rs.getTimestamp(7).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC),
                rs.getString(8)),
        limit,
        tenantId,
        leaseSeconds);
  }

  void complete(long id) {
    jdbc.update("DELETE FROM int_queue_message WHERE id = ?", id);
  }

  void retry(long id, long delaySeconds, String error) {
    jdbc.update(
        "UPDATE int_queue_message SET status = 'READY', locked_until = NULL,"
            + " next_attempt_at = DATEADD(second, ?, SYSUTCDATETIME()), last_error = ? WHERE id = ?",
        delaySeconds,
        error,
        id);
  }

  void dead(long id, String error) {
    jdbc.update(
        "UPDATE int_queue_message SET status = 'DEAD', locked_until = NULL,"
            + " died_at = SYSUTCDATETIME(), last_error = ? WHERE id = ?",
        error,
        id);
  }

  /**
   * Arrendamentos vencidos (consumidor morreu): voltam para a fila, ou morrem se já esgotaram as
   * tentativas.
   */
  int reclaimExpired(int maxAttempts) {
    int dead =
        jdbc.update(
            "UPDATE int_queue_message SET status = 'DEAD', locked_until = NULL,"
                + " died_at = SYSUTCDATETIME(), last_error = 'Consumidor não concluiu a tempo'"
                + " WHERE status = 'PROCESSING' AND locked_until < SYSUTCDATETIME() AND attempts >= ?",
            maxAttempts);
    int back =
        jdbc.update(
            "UPDATE int_queue_message SET status = 'READY', locked_until = NULL,"
                + " last_error = 'Consumidor não concluiu a tempo'"
                + " WHERE status = 'PROCESSING' AND locked_until < SYSUTCDATETIME()");
    return dead + back;
  }

  int purgeDead(int retentionDays) {
    return jdbc.update(
        "DELETE FROM int_queue_message WHERE status = 'DEAD'"
            + " AND died_at < DATEADD(day, ?, SYSUTCDATETIME())",
        -retentionDays);
  }

  // ---- administração (sempre filtrada por tenant) -------------------------------------

  List<Stat> stats(long tenantId) {
    return jdbc.query(
        "SELECT queue_name, status, COUNT_BIG(*), MIN(created_at) FROM int_queue_message"
            + " WHERE tenant_id = ? GROUP BY queue_name, status ORDER BY queue_name, status",
        (rs, i) ->
            new Stat(rs.getString(1), rs.getString(2), rs.getLong(3), utc(rs.getTimestamp(4))),
        tenantId);
  }

  List<Dead> dead(long tenantId, int limit) {
    return jdbc.query(
        "SELECT TOP (?) id, queue_name, environment, attempts, last_error, created_at, died_at"
            + " FROM int_queue_message WHERE tenant_id = ? AND status = 'DEAD' ORDER BY died_at DESC, id DESC",
        (rs, i) ->
            new Dead(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getInt(4),
                rs.getString(5),
                utc(rs.getTimestamp(6)),
                utc(rs.getTimestamp(7))),
        limit,
        tenantId);
  }

  boolean requeue(long tenantId, long id) {
    return jdbc.update(
            "UPDATE int_queue_message SET status = 'READY', attempts = 0, died_at = NULL,"
                + " next_attempt_at = SYSUTCDATETIME() WHERE id = ? AND tenant_id = ? AND status = 'DEAD'",
            id,
            tenantId)
        > 0;
  }

  boolean discard(long tenantId, long id) {
    return jdbc.update(
            "DELETE FROM int_queue_message WHERE id = ? AND tenant_id = ? AND status = 'DEAD'",
            id,
            tenantId)
        > 0;
  }

  private static Instant utc(Timestamp timestamp) {
    return timestamp == null
        ? null
        : timestamp.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
  }
}
