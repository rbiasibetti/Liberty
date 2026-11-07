package br.com.liberty.engine.script;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.read.ValueBinder;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.SqlIdentifiers;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.scripts.ScriptExecutor;
import br.com.liberty.snapshot.model.NamedQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tudo que um script pode pedir ao banco do tenant (o trabalhador não tem conexão): consultas
 * nomeadas do snapshot e busca de um valor por chave. Só leitura, com limite de linhas e prazo por
 * chamada; mensagens de erro nunca carregam texto do banco.
 */
final class ScriptHost implements ScriptExecutor.HostCalls {

  static final int MAX_ROWS = 1000;
  private static final int QUERY_TIMEOUT_SECONDS = 10;
  private static final Pattern READ_ONLY =
      Pattern.compile("^\\s*(select|with)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final TenantScope scope;
  private final Map<String, NamedQuery> queries = new HashMap<>();
  private final TenantCatalog catalog;
  private final NamedParameterJdbcTemplate jdbc;

  ScriptHost(
      TenantScope scope,
      List<NamedQuery> namedQueries,
      TenantCatalog catalog,
      NamedParameterJdbcTemplate jdbc) {
    this.scope = scope;
    namedQueries.forEach(q -> queries.put(q.name(), q));
    this.catalog = catalog;
    this.jdbc = jdbc;
  }

  @Override
  public String named(String name, String paramsJson) {
    NamedQuery query = queries.get(name);
    if (query == null) {
      throw new IllegalArgumentException("Consulta nomeada inexistente");
    }
    if (query.sql().contains(";") || !READ_ONLY.matcher(query.sql()).matches()) {
      throw new IllegalArgumentException("Consulta nomeada não é um SELECT simples");
    }
    JsonNode given = MAPPER.readTree(paramsJson);
    MapSqlParameterSource params = new MapSqlParameterSource();
    for (String param : query.params()) {
      if (!given.has(param)) {
        throw new IllegalArgumentException("Parâmetro ausente: " + param);
      }
      params.addValue(param, scalar(given.get(param)));
    }
    given
        .propertyNames()
        .forEach(
            key -> {
              if (!query.params().contains(key)) {
                throw new IllegalArgumentException("Parâmetro desconhecido");
              }
            });
    return TenantContext.supplyAs(scope, () -> run(query.sql(), params));
  }

  private String run(String sql, MapSqlParameterSource params) {
    ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
    String text = NamedParameterUtils.substituteNamedParameters(parsed, params);
    Object[] args = NamedParameterUtils.buildValueArray(parsed, params, null);
    return jdbc.getJdbcTemplate()
        .query(
            con -> {
              var ps = con.prepareStatement(text);
              ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
              ps.setMaxRows(MAX_ROWS + 1);
              new ArgumentPreparedStatementSetter(args).setValues(ps);
              return ps;
            },
            rs -> {
              ArrayNode rows = MAPPER.createArrayNode();
              ResultSetMetaData meta = rs.getMetaData();
              while (rs.next()) {
                if (rows.size() >= MAX_ROWS) {
                  throw new IllegalStateException("A consulta devolveu linhas demais");
                }
                rows.add(row(rs, meta));
              }
              return MAPPER.writeValueAsString(rows);
            });
  }

  @Override
  public String lookup(String table, String keyColumn, String keyJson, String valueColumn) {
    TableInfo info = catalog.table(scope, table);
    ColumnInfo key =
        info.column(keyColumn)
            .orElseThrow(() -> new IllegalArgumentException("Coluna inexistente"));
    ColumnInfo value =
        info.column(valueColumn)
            .orElseThrow(() -> new IllegalArgumentException("Coluna inexistente"));
    Object keyValue = scalar(MAPPER.readTree(keyJson));
    if (keyValue == null) {
      return "null";
    }
    Object bound;
    try {
      bound =
          ValueBinder.bind(
              key, String.valueOf(keyValue instanceof BigDecimal d ? d.toPlainString() : keyValue));
    } catch (ValueBinder.InvalidValueException | ValueBinder.UnsupportedColumnException e) {
      throw new IllegalArgumentException("Chave inválida para a coluna");
    }
    String sql =
        "SELECT TOP 2 "
            + SqlIdentifiers.column(info, value.name())
            + " FROM "
            + SqlIdentifiers.table(info)
            + " WHERE "
            + SqlIdentifiers.column(info, key.name())
            + " = :k";
    return TenantContext.supplyAs(
        scope,
        () ->
            jdbc.getJdbcTemplate()
                .query(
                    con -> {
                      var ps = con.prepareStatement(sql.replace(":k", "?"));
                      ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                      ps.setObject(1, bound);
                      return ps;
                    },
                    rs -> {
                      if (!rs.next()) {
                        return "null";
                      }
                      ObjectNode holder = MAPPER.createObjectNode();
                      put(holder, "v", rs.getObject(1));
                      String json = MAPPER.writeValueAsString(holder.get("v"));
                      if (rs.next()) {
                        throw new IllegalStateException("A chave corresponde a mais de uma linha");
                      }
                      return json;
                    }));
  }

  private static Object scalar(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    throw new IllegalArgumentException("Esperado um valor simples");
  }

  private static ObjectNode row(ResultSet rs, ResultSetMetaData meta) throws SQLException {
    ObjectNode row = MAPPER.createObjectNode();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      put(row, meta.getColumnLabel(i), rs.getObject(i));
    }
    return row;
  }

  private static void put(ObjectNode node, String name, Object value) {
    switch (value) {
      case null -> node.putNull(name);
      case BigDecimal d -> node.put(name, d);
      case Long l -> node.put(name, l);
      case Integer i -> node.put(name, i);
      case Short s -> node.put(name, s);
      case Double d -> node.put(name, d);
      case Float f -> node.put(name, f);
      case Boolean b -> node.put(name, b);
      case byte[] bytes -> node.put(name, Base64.getEncoder().encodeToString(bytes));
      default -> node.put(name, value.toString());
    }
  }
}
