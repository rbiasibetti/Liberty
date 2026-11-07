package br.com.liberty.infrastructure.catalog;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Introspecção pelo {@link DatabaseMetaData} do JDBC, com o usuário de privilégio mínimo do tenant:
 * o que ele não enxerga não existe para a plataforma. Os padrões do JDBC são LIKE, então {@code _}
 * e {@code %} são escapados e o resultado é conferido por igualdade (um pedido por {@code ped_ido}
 * nunca devolve {@code pedXido}). Esquemas do sistema nunca são expostos.
 */
public final class JdbcCatalogIntrospector implements CatalogIntrospector {

  private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};
  private static final int LIST_LIMIT = 5_000;

  private final TenantPoolRegistry registry;

  public JdbcCatalogIntrospector(TenantPoolRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Optional<TableInfo> introspect(TenantScope scope, TableRef reference) {
    if (isSystemSchema(reference.schema())) {
      return Optional.empty();
    }
    try (Connection connection = registry.pool(scope).getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      String catalog = connection.getCatalog();
      String escape = metadata.getSearchStringEscape();
      Optional<Found> found = findTable(metadata, catalog, escape, reference);
      if (found.isEmpty()) {
        return Optional.empty();
      }
      Found table = found.get();
      Map<String, Integer> primaryKey = primaryKey(metadata, catalog, table);
      List<ColumnInfo> columns = columns(metadata, catalog, escape, table, primaryKey);
      if (columns.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new TableInfo(table.schema(), table.name(), table.kind(), columns));
    } catch (SQLException e) {
      throw new IllegalStateException("Falha ao ler o catálogo do tenant " + scope, e);
    }
  }

  @Override
  public List<TableSummary> listTables(TenantScope scope) {
    try (Connection connection = registry.pool(scope).getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      List<TableSummary> result = new ArrayList<>();
      try (ResultSet rs = metadata.getTables(connection.getCatalog(), null, "%", TABLE_TYPES)) {
        while (rs.next() && result.size() < LIST_LIMIT) {
          String schema = rs.getString("TABLE_SCHEM");
          String name = rs.getString("TABLE_NAME");
          // só nomes que a plataforma consegue referenciar: o editor nunca oferece o que seria
          // recusado
          boolean usable =
              TableRef.PLAIN_IDENTIFIER.matcher(schema).matches()
                  && TableRef.PLAIN_IDENTIFIER.matcher(name).matches();
          if (usable && !isSystemSchema(schema)) {
            result.add(
                new TableSummary(
                    schema, rs.getString("TABLE_NAME"), kind(rs.getString("TABLE_TYPE"))));
          }
        }
      }
      result.sort(Comparator.comparing(TableSummary::schema).thenComparing(TableSummary::name));
      return List.copyOf(result);
    } catch (SQLException e) {
      throw new IllegalStateException("Falha ao listar o catálogo do tenant " + scope, e);
    }
  }

  private record Found(String schema, String name, TableInfo.Kind kind) {}

  private static Optional<Found> findTable(
      DatabaseMetaData metadata, String catalog, String escape, TableRef reference)
      throws SQLException {
    List<Found> candidates = new ArrayList<>();
    try (ResultSet rs =
        metadata.getTables(
            catalog,
            escape(reference.schema(), escape),
            escape(reference.name(), escape),
            TABLE_TYPES)) {
      while (rs.next()) {
        candidates.add(
            new Found(
                rs.getString("TABLE_SCHEM"),
                rs.getString("TABLE_NAME"),
                kind(rs.getString("TABLE_TYPE"))));
      }
    }
    for (Found candidate : candidates) { // grafia exata vence
      if (candidate.schema().equals(reference.schema())
          && candidate.name().equals(reference.name())) {
        return Optional.of(candidate);
      }
    }
    List<Found> folded =
        candidates.stream()
            .filter(
                c ->
                    c.schema().equalsIgnoreCase(reference.schema())
                        && c.name().equalsIgnoreCase(reference.name()))
            .toList();
    return folded.size() == 1 ? Optional.of(folded.getFirst()) : Optional.empty();
  }

  private static Map<String, Integer> primaryKey(
      DatabaseMetaData metadata, String catalog, Found table) throws SQLException {
    Map<String, Integer> result = new HashMap<>();
    try (ResultSet rs = metadata.getPrimaryKeys(catalog, table.schema(), table.name())) {
      while (rs.next()) {
        result.put(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"));
      }
    }
    return result;
  }

  private static List<ColumnInfo> columns(
      DatabaseMetaData metadata,
      String catalog,
      String escape,
      Found table,
      Map<String, Integer> primaryKey)
      throws SQLException {
    List<ColumnInfo> columns = new ArrayList<>();
    try (ResultSet rs =
        metadata.getColumns(
            catalog, escape(table.schema(), escape), escape(table.name(), escape), "%")) {
      while (rs.next()) {
        if (!table.schema().equals(rs.getString("TABLE_SCHEM"))
            || !table.name().equals(rs.getString("TABLE_NAME"))) {
          continue; // o padrão LIKE pode casar outra tabela; só vale a igualdade exata
        }
        String name = rs.getString("COLUMN_NAME");
        String typeName = rs.getString("TYPE_NAME");
        boolean autoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
        boolean computed = "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"));
        boolean rowVersion =
            typeName.equalsIgnoreCase("timestamp") || typeName.equalsIgnoreCase("rowversion");
        columns.add(
            new ColumnInfo(
                name,
                typeName,
                kind(rs.getInt("DATA_TYPE"), typeName),
                rs.getInt("COLUMN_SIZE"),
                rs.getInt("DECIMAL_DIGITS"),
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                rs.getString("COLUMN_DEF") != null,
                autoIncrement || computed || rowVersion,
                rs.getInt("ORDINAL_POSITION"),
                primaryKey.getOrDefault(name, 0)));
      }
    }
    columns.sort(Comparator.comparingInt(ColumnInfo::ordinal));
    return columns;
  }

  static boolean isSystemSchema(String schema) {
    String lower = schema.toLowerCase(Locale.ROOT);
    return lower.equals("sys")
        || lower.equals("information_schema")
        || lower.equals("guest")
        || lower.startsWith("db_");
  }

  /** Escapa os curingas do LIKE (e o próprio escape) para a busca ser por nome literal. */
  static String escape(String value, String escape) {
    StringBuilder out = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (c == '_' || c == '%' || c == '[' || c == ']' || String.valueOf(c).equals(escape)) {
        out.append(escape);
      }
      out.append(c);
    }
    return out.toString();
  }

  private static TableInfo.Kind kind(String tableType) {
    return "VIEW".equalsIgnoreCase(tableType) ? TableInfo.Kind.VIEW : TableInfo.Kind.TABLE;
  }

  static ColumnInfo.Kind kind(int jdbcType, String typeName) {
    String type = typeName.toLowerCase(Locale.ROOT);
    if (type.equals("uniqueidentifier")) {
      return ColumnInfo.Kind.UUID;
    }
    if (type.equals("xml")) {
      return ColumnInfo.Kind.XML;
    }
    if (type.equals("datetimeoffset")) {
      return ColumnInfo.Kind.DATE_TIME_OFFSET;
    }
    if (type.equals("money") || type.equals("smallmoney")) {
      return ColumnInfo.Kind.DECIMAL;
    }
    return switch (jdbcType) {
      case Types.CHAR,
          Types.VARCHAR,
          Types.LONGVARCHAR,
          Types.NCHAR,
          Types.NVARCHAR,
          Types.LONGNVARCHAR ->
          ColumnInfo.Kind.STRING;
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> ColumnInfo.Kind.INTEGER;
      case Types.DECIMAL, Types.NUMERIC -> ColumnInfo.Kind.DECIMAL;
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> ColumnInfo.Kind.FLOAT;
      case Types.BIT, Types.BOOLEAN -> ColumnInfo.Kind.BOOLEAN;
      case Types.DATE -> ColumnInfo.Kind.DATE;
      case Types.TIME -> ColumnInfo.Kind.TIME;
      case Types.TIMESTAMP -> ColumnInfo.Kind.DATE_TIME;
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> ColumnInfo.Kind.BINARY;
      case Types.SQLXML -> ColumnInfo.Kind.XML;
      default -> ColumnInfo.Kind.OTHER;
    };
  }
}
