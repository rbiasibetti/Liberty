package br.com.liberty.admin;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta da auditoria de execuções de um tenant. A tabela é particionada por mês: toda consulta
 * tem janela de tempo (padrão: últimas 24 h; no máximo 31 dias) para o banco só tocar nas partições
 * necessárias. Só metadados e a mensagem de erro já mascarada (ADR 0029); não há payload nem SQL.
 */
@Service
class AuditQueryService {

  /** Uma execução auditada. */
  record Row(
      long id,
      Instant createdAt,
      String integration,
      String environment,
      String method,
      String route,
      int status,
      long durationMs,
      Long dbDurationMs,
      Long scriptDurationMs,
      String errorCode,
      String errorMessage,
      String requestId,
      String clientId) {}

  /** Página de resultados; {@code hasMore} diz se há outra depois dela. */
  record Page(List<Row> items, int page, int size, boolean hasMore) {}

  static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
  static final Duration MAX_WINDOW = Duration.ofDays(31);
  private static final int MAX_PAGE_SIZE = 100;
  private static final Pattern STATUS = Pattern.compile("[1-5]xx|[1-5][0-9]{2}");
  private static final Pattern KEY = Pattern.compile("[a-z0-9-]{1,100}");
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final TenantRepository tenants;
  private final EntityManager entityManager;

  AuditQueryService(TenantRepository tenants, EntityManager entityManager) {
    this.tenants = tenants;
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  Page search(
      String slug,
      Instant from,
      Instant to,
      String integration,
      String status,
      String requestId,
      int page,
      int size) {
    long tenantId =
        tenants
            .findBySlug(slug)
            .orElseThrow(
                () ->
                    new ProblemException(
                        404, "not-found", "Não encontrado", "Tenant não encontrado"))
            .getId();
    Instant end = to == null ? Instant.now() : to;
    Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
    List<Violation> problems = new ArrayList<>();
    if (!start.isBefore(end)) {
      problems.add(new Violation("from", "invalid_range", "O início deve ser anterior ao fim"));
    } else if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
      problems.add(
          new Violation("from", "range_too_long", "A janela de consulta é de no máximo 31 dias"));
    }
    if (integration != null && !KEY.matcher(integration).matches()) {
      problems.add(new Violation("integration", "invalid_value", "Chave de integração inválida"));
    }
    if (status != null && !STATUS.matcher(status).matches()) {
      problems.add(
          new Violation("status", "invalid_value", "Use um status (500) ou uma classe (5xx)"));
    }
    if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
      problems.add(
          new Violation("requestId", "invalid_value", "Identificador de requisição inválido"));
    }
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      problems.add(
          new Violation("size", "out_of_range", "Página a partir de 0 e tamanho de 1 a 100"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }

    StringBuilder sql =
        new StringBuilder(
            "SELECT id, created_at, integration_key, environment, http_method, route, status_code,"
                + " duration_ms, db_duration_ms, script_duration_ms, error_code, error_message,"
                + " request_id, client_id FROM int_audit_execution"
                + " WHERE tenant_id = :tenant AND created_at >= :from AND created_at < :to");
    if (integration != null) {
      sql.append(" AND integration_key = :integration");
    }
    if (status != null) {
      sql.append(
          status.endsWith("xx")
              ? " AND status_code >= :statusFrom AND status_code < :statusTo"
              : " AND status_code = :status");
    }
    if (requestId != null) {
      sql.append(" AND request_id = :requestId");
    }
    sql.append(
        " ORDER BY created_at DESC, id DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
    var query =
        entityManager
            .createNativeQuery(sql.toString())
            .setParameter("tenant", tenantId)
            .setParameter("from", LocalDateTime.ofInstant(start, ZoneOffset.UTC))
            .setParameter("to", LocalDateTime.ofInstant(end, ZoneOffset.UTC))
            .setParameter("offset", page * size)
            .setParameter("limit", size + 1);
    if (integration != null) {
      query.setParameter("integration", integration);
    }
    if (status != null) {
      if (status.endsWith("xx")) {
        int hundreds = (status.charAt(0) - '0') * 100;
        query.setParameter("statusFrom", hundreds).setParameter("statusTo", hundreds + 100);
      } else {
        query.setParameter("status", Integer.parseInt(status));
      }
    }
    if (requestId != null) {
      query.setParameter("requestId", requestId);
    }
    List<Object[]> found = query.getResultList();
    List<Row> rows = new ArrayList<>();
    for (Object[] r : found.subList(0, Math.min(found.size(), size))) {
      rows.add(
          new Row(
              ((Number) r[0]).longValue(),
              instant(r[1]),
              (String) r[2],
              (String) r[3],
              (String) r[4],
              (String) r[5],
              ((Number) r[6]).intValue(),
              ((Number) r[7]).longValue(),
              number(r[8]),
              number(r[9]),
              (String) r[10],
              (String) r[11],
              (String) r[12],
              (String) r[13]));
    }
    return new Page(rows, page, size, found.size() > size);
  }

  private static Long number(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  /**
   * A coluna guarda hora UTC sem fuso; o driver pode devolvê-la como Timestamp ou LocalDateTime.
   */
  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
    if (value instanceof LocalDateTime local) {
      return local.toInstant(ZoneOffset.UTC);
    }
    return (Instant) value;
  }
}
