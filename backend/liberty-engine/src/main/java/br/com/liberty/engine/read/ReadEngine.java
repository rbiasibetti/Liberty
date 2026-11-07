package br.com.liberty.engine.read;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.route.PublishedIntegration;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.catalog.UnknownDatabaseObjectException;
import br.com.liberty.infrastructure.tenancy.TenantUnavailableException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executa uma integração de leitura: confere a entrada, localiza a tabela no catálogo do tenant,
 * monta o SELECT paginado e o roda em transação somente leitura no banco do tenant, com prazo.
 * Erros viram {@link ProblemException} com mensagens genéricas; os detalhes vão só para o log.
 */
public final class ReadEngine {

  private static final Logger LOG = LoggerFactory.getLogger(ReadEngine.class);

  private final TenantCatalog catalog;
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate readOnly;

  public ReadEngine(
      TenantCatalog catalog, NamedParameterJdbcTemplate jdbc, TransactionTemplate readOnly) {
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.readOnly = readOnly;
  }

  public ReadResult execute(
      TenantScope scope,
      PublishedIntegration integration,
      Map<String, String> pathVariables,
      Map<String, List<String>> query) {
    var select = integration.snapshot().operation().select();
    ParsedInput input = InputParser.parse(integration.snapshot(), pathVariables, query);
    SelectPlan plan = plan(scope, integration, select, input);
    List<Map<String, Object>> rows = run(scope, plan);
    if (plan.single()) {
      if (rows.isEmpty()) {
        throw new ProblemException(
            404, "not-found", "Não encontrado", "Nenhum registro corresponde aos filtros");
      }
      if (rows.size() > 1) {
        throw new ProblemException(
            409,
            "ambiguous-result",
            "Resultado ambíguo",
            "Os filtros correspondem a mais de um registro");
      }
      return ReadResult.single(rows.getFirst());
    }
    boolean hasMore = rows.size() > plan.limit();
    return ReadResult.page(
        hasMore ? rows.subList(0, plan.limit()) : rows, plan.limit(), plan.offset(), hasMore);
  }

  private SelectPlan plan(
      TenantScope scope,
      PublishedIntegration integration,
      br.com.liberty.snapshot.model.Select select,
      ParsedInput input) {
    try {
      TableInfo table = catalog.table(scope, select.table());
      return SelectPlanner.plan(select, table, input);
    } catch (UnknownDatabaseObjectException e) {
      // o snapshot publicado cita algo que não existe mais: o schema do cliente mudou
      catalog.invalidate(scope);
      LOG.error(
          "Integração {} ({}) referencia objeto inexistente: {}",
          integration.key(),
          scope,
          e.getMessage());
      throw new ProblemException(
          503,
          "schema-changed",
          "Integração indisponível",
          "A estrutura do banco do cliente mudou; tente novamente em instantes");
    } catch (ValueBinder.UnsupportedColumnException e) {
      LOG.error(
          "Integração {} ({}) configurada com tipo sem suporte: {}",
          integration.key(),
          scope,
          e.getMessage());
      throw new ProblemException(
          500, "internal", "Erro interno", "A integração está mal configurada");
    } catch (TenantUnavailableException e) {
      throw tenantUnavailable(scope, e);
    }
  }

  private List<Map<String, Object>> run(TenantScope scope, SelectPlan plan) {
    try {
      return TenantContext.supplyAs(
          scope,
          () ->
              readOnly.execute(
                  status ->
                      jdbc.query(plan.sql(), plan.params(), (rs, n) -> row(rs, plan.columns()))));
    } catch (TenantUnavailableException e) {
      LOG.warn("Banco do tenant {} indisponível: {}", scope, e.getMessage());
      throw new ProblemException(
          503,
          "tenant-unavailable",
          "Serviço indisponível",
          "O banco do cliente está indisponível");
    } catch (DataAccessException e) {
      if (catalog.onDatabaseError(scope, e)) {
        throw new ProblemException(
            503,
            "schema-changed",
            "Integração indisponível",
            "A estrutura do banco do cliente mudou; tente novamente em instantes");
      }
      if (e instanceof QueryTimeoutException || e.getCause() instanceof SQLTimeoutException) {
        throw new ProblemException(
            504,
            "query-timeout",
            "Tempo esgotado",
            "A consulta ao banco do cliente excedeu o prazo");
      }
      LOG.error("Falha ao consultar o banco do tenant {}", scope, e);
      throw new ProblemException(
          500, "internal", "Erro interno", "Falha ao consultar o banco do cliente");
    }
  }

  private static ProblemException tenantUnavailable(
      TenantScope scope, TenantUnavailableException e) {
    LOG.warn("Banco do tenant {} indisponível: {}", scope, e.getMessage());
    return new ProblemException(
        503, "tenant-unavailable", "Serviço indisponível", "O banco do cliente está indisponível");
  }

  private static Map<String, Object> row(ResultSet rs, List<ColumnInfo> columns)
      throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < columns.size(); i++) {
      row.put(columns.get(i).name(), value(rs, i + 1, columns.get(i)));
    }
    return row;
  }

  private static Object value(ResultSet rs, int index, ColumnInfo column) throws SQLException {
    Object value =
        switch (column.kind()) {
          case INTEGER -> rs.getLong(index);
          case DECIMAL -> rs.getBigDecimal(index);
          case FLOAT -> rs.getDouble(index);
          case BOOLEAN -> rs.getBoolean(index);
          case DATE -> rs.getObject(index, java.time.LocalDate.class);
          case TIME -> rs.getObject(index, java.time.LocalTime.class);
          case DATE_TIME -> rs.getObject(index, java.time.LocalDateTime.class);
          case DATE_TIME_OFFSET -> rs.getObject(index, java.time.OffsetDateTime.class);
          case BINARY -> rs.getBytes(index);
          case STRING, UUID, XML, OTHER -> rs.getString(index);
        };
    if (value == null || rs.wasNull()) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof java.time.LocalDateTime dateTime) {
      return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
    }
    if (value instanceof java.time.LocalTime time) {
      return java.time.format.DateTimeFormatter.ISO_LOCAL_TIME.format(time);
    }
    if (value instanceof java.time.OffsetDateTime offset) {
      return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offset);
    }
    if (value instanceof java.time.LocalDate date) {
      return date.toString();
    }
    return value;
  }
}
