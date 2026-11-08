package br.com.liberty.engine.exports;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Acesso a {@code int_export_job}. Toda consulta e toda mudança filtram por tenant. */
public class ExportRepository {

  /** Um pedido de exportação. */
  public record Job(
      long id,
      long tenantId,
      String requestedBy,
      String status,
      String format,
      String filterJson,
      String objectKey,
      String error,
      Instant createdAt,
      Instant expiresAt) {}

  private static final String COLUMNS =
      "id, tenant_id, requested_by, status, format, filter_json, object_key, error_message,"
          + " created_at, expires_at";

  private final JdbcTemplate jdbc;

  public ExportRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  Long tenantId(String slug) {
    var found = jdbc.queryForList("SELECT id FROM int_tenant WHERE slug = ?", Long.class, slug);
    return found.isEmpty() ? null : found.getFirst();
  }

  String slug(long tenantId) {
    var found =
        jdbc.queryForList("SELECT slug FROM int_tenant WHERE id = ?", String.class, tenantId);
    return found.isEmpty() ? null : found.getFirst();
  }

  long create(long tenantId, String user, String format, String filterJson) {
    return jdbc.queryForObject(
        "INSERT INTO int_export_job (tenant_id, requested_by, status, format, filter_json)"
            + " OUTPUT INSERTED.id VALUES (?, ?, 'PENDING', ?, ?)",
        Long.class,
        tenantId,
        user,
        format,
        filterJson);
  }

  long active(long tenantId) {
    return jdbc.queryForObject(
        "SELECT COUNT_BIG(*) FROM int_export_job WHERE tenant_id = ? AND status IN ('PENDING', 'PROCESSING')",
        Long.class,
        tenantId);
  }

  Optional<Job> find(long tenantId, long id) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM int_export_job WHERE id = ? AND tenant_id = ?",
            (rs, i) -> job(rs),
            id,
            tenantId)
        .stream()
        .findFirst();
  }

  List<Job> recent(long tenantId, int limit) {
    return jdbc.query(
        "SELECT TOP (?) " + COLUMNS + " FROM int_export_job WHERE tenant_id = ? ORDER BY id DESC",
        (rs, i) -> job(rs),
        limit,
        tenantId);
  }

  void processing(long tenantId, long id) {
    jdbc.update(
        "UPDATE int_export_job SET status = 'PROCESSING', updated_at = SYSUTCDATETIME()"
            + " WHERE id = ? AND tenant_id = ? AND status IN ('PENDING', 'PROCESSING')",
        id,
        tenantId);
  }

  void completed(long tenantId, long id, String objectKey, Instant expiresAt) {
    jdbc.update(
        "UPDATE int_export_job SET status = 'COMPLETED', object_key = ?, error_message = NULL,"
            + " expires_at = ?, updated_at = SYSUTCDATETIME() WHERE id = ? AND tenant_id = ?",
        objectKey,
        Timestamp.valueOf(java.time.LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)),
        id,
        tenantId);
  }

  void failed(long tenantId, long id, String error) {
    jdbc.update(
        "UPDATE int_export_job SET status = 'FAILED', error_message = ?,"
            + " updated_at = SYSUTCDATETIME() WHERE id = ? AND tenant_id = ?",
        error,
        id,
        tenantId);
  }

  /** Arquivos vencidos, ainda registrados. */
  List<Job> expired(int limit) {
    return jdbc.query(
        "SELECT TOP (?) "
            + COLUMNS
            + " FROM int_export_job WHERE status = 'COMPLETED' AND object_key IS NOT NULL"
            + " AND expires_at < SYSUTCDATETIME() ORDER BY expires_at",
        (rs, i) -> job(rs),
        limit);
  }

  void cleared(long id) {
    jdbc.update(
        "UPDATE int_export_job SET object_key = NULL, updated_at = SYSUTCDATETIME() WHERE id = ?",
        id);
  }

  private static Job job(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Job(
        rs.getLong(1),
        rs.getLong(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getString(7),
        rs.getString(8),
        utc(rs.getTimestamp(9)),
        utc(rs.getTimestamp(10)));
  }

  private static Instant utc(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
  }
}
