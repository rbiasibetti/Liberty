package br.com.liberty.engine.write;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.trace.SqlTrace;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.SqlIdentifiers;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executa a árvore de linhas já validada no banco do tenant, dentro da transação aberta pelo
 * chamador (qualquer exceção a desfaz por inteiro). INSERT captura a chave gerada com {@code OUTPUT
 * ... INTO @tabela}, forma que vale também para tabelas com gatilhos; filhos folha entram em lotes;
 * UPSERT é UPDATE com bloqueio de intervalo seguido de INSERT (sem MERGE); UPDATE exige exatamente
 * uma linha. Todo valor é parâmetro; só nomes do catálogo viram texto de SQL.
 */
final class WriteExecutor {

  static final int BATCH_SIZE = 500;

  /** Resultado da execução, sem o formato HTTP. */
  record Outcome(boolean created, ObjectNode body) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final SqlTrace trace;

  WriteExecutor(NamedParameterJdbcTemplate jdbc) {
    this(jdbc, null);
  }

  /** Com {@code trace}, cada comando é registrado (usado só no sandbox). */
  WriteExecutor(NamedParameterJdbcTemplate jdbc, SqlTrace trace) {
    this.jdbc = jdbc;
    this.trace = trace;
  }

  private int update(String sql, MapSqlParameterSource params) {
    long start = System.nanoTime();
    int rows = jdbc.update(sql, params);
    note(sql, rows, start);
    return rows;
  }

  private void note(String sql, int rows, long startNanos) {
    if (trace != null) {
      trace.record(sql, rows, (System.nanoTime() - startNanos) / 1_000_000);
    }
  }

  Outcome insert(RowData root) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    Object key = insertRow(root, null, true, counts);
    ObjectNode body = BodyReader.mapper().createObjectNode();
    if (key != null) {
      put(body.putObject("key"), root.plan().primaryKey().name(), key);
    }
    counts(body, counts);
    return new Outcome(true, body);
  }

  Outcome upsert(RowData root) {
    int updated = update(root, "UPSERT");
    if (updated == 1) {
      ObjectNode body = BodyReader.mapper().createObjectNode().put("result", "updated");
      keyOf(root, body);
      return new Outcome(false, body);
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    insertRow(root, null, true, counts);
    ObjectNode body = BodyReader.mapper().createObjectNode().put("result", "inserted");
    keyOf(root, body);
    return new Outcome(true, body);
  }

  Outcome updateOnly(RowData root) {
    if (update(root, "UPDATE") == 0) {
      throw new ProblemException(
          404, "not-found", "Não encontrado", "Nenhum registro corresponde à chave informada");
    }
    ObjectNode body = BodyReader.mapper().createObjectNode().put("updated", 1);
    keyOf(root, body);
    return new Outcome(false, body);
  }

  // ---- INSERT ----------------------------------------------------------------------------

  private Object insertRow(
      RowData row, Object parentKey, boolean root, Map<String, Integer> counts) {
    WritePlan plan = row.plan();
    List<RowData.Cell> cells = withForeignKey(row.cells(), plan, parentKey);
    boolean capture = plan.primaryKey() != null && (root || plan.hasChildren());
    MapSqlParameterSource params = params(cells);
    Object key = null;
    if (capture) {
      String statement = insertSql(plan, cells, true);
      long start = System.nanoTime();
      key = jdbc.query(statement, params, rs -> rs.next() ? readKey(rs, plan.primaryKey()) : null);
      note(statement, key == null ? 0 : 1, start);
      if (key == null) {
        throw new IllegalStateException("O banco não devolveu a chave inserida");
      }
    } else {
      update(insertSql(plan, cells, false), params);
    }
    counts.merge(plan.table().name(), 1, Integer::sum);
    for (int i = 0; i < plan.children().size(); i++) {
      List<RowData> rows = row.children().get(i);
      if (rows.isEmpty()) {
        continue;
      }
      WritePlan child = plan.children().get(i);
      if (child.hasChildren()) {
        for (RowData childRow : rows) {
          insertRow(childRow, key, false, counts);
        }
      } else {
        insertBatch(child, rows, key, counts);
      }
    }
    return key;
  }

  /** Filhos folha: execuções consecutivas com as mesmas colunas viajam em lotes. */
  private void insertBatch(
      WritePlan plan, List<RowData> rows, Object parentKey, Map<String, Integer> counts) {
    List<SqlParameterSource> chunk = new ArrayList<>();
    String sql = null;
    String signature = null;
    for (RowData row : rows) {
      List<RowData.Cell> cells = withForeignKey(row.cells(), plan, parentKey);
      String current = signature(cells);
      if (!current.equals(signature) || chunk.size() >= BATCH_SIZE) {
        flush(sql, chunk);
        chunk.clear();
        sql = insertSql(plan, cells, false);
        signature = current;
      }
      chunk.add(params(cells));
    }
    flush(sql, chunk);
    counts.merge(plan.table().name(), rows.size(), Integer::sum);
  }

  private void flush(String sql, List<SqlParameterSource> chunk) {
    if (!chunk.isEmpty()) {
      long start = System.nanoTime();
      jdbc.batchUpdate(sql, chunk.toArray(SqlParameterSource[]::new));
      note(sql, chunk.size(), start);
    }
  }

  private static String signature(List<RowData.Cell> cells) {
    StringBuilder out = new StringBuilder();
    for (RowData.Cell cell : cells) {
      out.append(cell.column().name()).append(',');
    }
    return out.toString();
  }

  private static List<RowData.Cell> withForeignKey(
      List<RowData.Cell> cells, WritePlan plan, Object parentKey) {
    if (plan.foreignKey() == null) {
      return cells;
    }
    List<RowData.Cell> all = new ArrayList<>(cells.size() + 1);
    all.add(new RowData.Cell(plan.foreignKey(), parentKey));
    all.addAll(cells);
    return all;
  }

  static String insertSql(WritePlan plan, List<RowData.Cell> cells, boolean capture) {
    String table = SqlIdentifiers.table(plan.table());
    StringBuilder sql = new StringBuilder();
    String pk = capture ? SqlIdentifiers.column(plan.table(), plan.primaryKey().name()) : null;
    if (capture) {
      sql.append("SET NOCOUNT ON; DECLARE @o TABLE (")
          .append(pk)
          .append(' ')
          .append(SqlTypes.declaration(plan.primaryKey()))
          .append("); ");
    }
    sql.append("INSERT INTO ").append(table);
    if (cells.isEmpty()) {
      sql.append(capture ? " OUTPUT INSERTED." + pk + " INTO @o" : "").append(" DEFAULT VALUES");
    } else {
      StringBuilder columns = new StringBuilder();
      StringBuilder values = new StringBuilder();
      for (int i = 0; i < cells.size(); i++) {
        columns
            .append(i == 0 ? "" : ", ")
            .append(SqlIdentifiers.column(plan.table(), cells.get(i).column().name()));
        values.append(i == 0 ? "" : ", ").append(":p").append(i);
      }
      sql.append(" (").append(columns).append(')');
      sql.append(capture ? " OUTPUT INSERTED." + pk + " INTO @o" : "");
      sql.append(" VALUES (").append(values).append(')');
    }
    if (capture) {
      sql.append("; SELECT ").append(pk).append(" FROM @o;");
    }
    return sql.toString();
  }

  // ---- UPDATE / UPSERT ----------------------------------------------------------------

  /** Devolve as linhas atualizadas; mais de uma desfaz tudo (a chave não era única). */
  private int update(RowData root, String operation) {
    WritePlan plan = root.plan();
    List<RowData.Cell> keys = new ArrayList<>();
    List<RowData.Cell> sets = new ArrayList<>();
    for (RowData.Cell cell : root.cells()) {
      boolean key = plan.keyColumns().stream().anyMatch(k -> k.name().equals(cell.column().name()));
      (key ? keys : sets).add(cell);
    }
    String table = SqlIdentifiers.table(plan.table());
    MapSqlParameterSource params = new MapSqlParameterSource();
    StringBuilder sql = new StringBuilder("UPDATE ").append(table);
    if ("UPSERT".equals(operation)) {
      // bloqueio de intervalo: duas requisições da mesma chave inexistente se enfileiram em vez de
      // ambas inserirem
      sql.append(" WITH (UPDLOCK, SERIALIZABLE)");
    }
    sql.append(" SET ");
    if (sets.isEmpty()) {
      String first = SqlIdentifiers.column(plan.table(), keys.getFirst().column().name());
      sql.append(first).append(" = ").append(first);
    }
    for (int i = 0; i < sets.size(); i++) {
      sql.append(i == 0 ? "" : ", ")
          .append(SqlIdentifiers.column(plan.table(), sets.get(i).column().name()))
          .append(" = :s")
          .append(i);
      bind(params, "s" + i, sets.get(i));
    }
    sql.append(" WHERE ");
    for (int i = 0; i < keys.size(); i++) {
      sql.append(i == 0 ? "" : " AND ")
          .append(SqlIdentifiers.column(plan.table(), keys.get(i).column().name()))
          .append(" = :k")
          .append(i);
      bind(params, "k" + i, keys.get(i));
    }
    int updated = update(sql.toString(), params);
    if (updated > 1) {
      throw new ProblemException(
          409,
          "ambiguous-key",
          "Chave ambígua",
          "A chave informada corresponde a mais de um registro");
    }
    return updated;
  }

  private void keyOf(RowData root, ObjectNode body) {
    ObjectNode key = body.putObject("key");
    for (RowData.Cell cell : root.cells()) {
      if (root.plan().keyColumns().stream().anyMatch(k -> k.name().equals(cell.column().name()))) {
        put(key, cell.column().name(), cell.value());
      }
    }
  }

  // ---- utilitários ------------------------------------------------------------------------

  private static MapSqlParameterSource params(List<RowData.Cell> cells) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (int i = 0; i < cells.size(); i++) {
      bind(params, "p" + i, cells.get(i));
    }
    return params;
  }

  private static void bind(MapSqlParameterSource params, String name, RowData.Cell cell) {
    params.addValue(name, cell.value(), SqlTypes.jdbcType(cell.column().kind()));
  }

  private static Object readKey(ResultSet rs, ColumnInfo pk) throws SQLException {
    return switch (pk.kind()) {
      case INTEGER -> rs.getLong(1);
      case DECIMAL -> rs.getBigDecimal(1);
      default -> rs.getString(1);
    };
  }

  private static void counts(ObjectNode body, Map<String, Integer> counts) {
    ObjectNode rows = body.putObject("rows");
    counts.forEach(rows::put);
  }

  private static void put(ObjectNode node, String name, Object value) {
    switch (value) {
      case null -> node.putNull(name);
      case Long l -> node.put(name, l);
      case Integer i -> node.put(name, i);
      case BigDecimal d -> node.put(name, d);
      case Boolean b -> node.put(name, b);
      case Double d -> node.put(name, d);
      case byte[] bytes -> node.put(name, Base64.getEncoder().encodeToString(bytes));
      default -> node.put(name, value.toString());
    }
  }
}
