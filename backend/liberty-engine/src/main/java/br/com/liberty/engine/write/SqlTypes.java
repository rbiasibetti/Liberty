package br.com.liberty.engine.write;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.sql.Types;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Tipos SQL: declaração de variável de tabela para a chave capturada e tipo JDBC de parâmetros. */
final class SqlTypes {

  private static final Set<String> INTEGERS = Set.of("tinyint", "smallint", "int", "bigint");
  private static final Set<String> TEXTS = Set.of("char", "varchar", "nchar", "nvarchar");
  private static final Pattern FIRST_WORD = Pattern.compile("[a-z]+");

  private SqlTypes() {}

  /** Tipo da coluna para um {@code DECLARE @t TABLE}; falha se não for um tipo de chave comum. */
  static String declaration(ColumnInfo column) {
    String type = baseName(column);
    boolean supported =
        switch (column.kind()) {
          case INTEGER -> INTEGERS.contains(type);
          case UUID -> true;
          case STRING -> TEXTS.contains(type);
          case DECIMAL -> type.equals("decimal") || type.equals("numeric");
          default -> false;
        };
    if (!supported) {
      throw new WritePlanner.ConfigurationException(
          "Tipo de chave sem suporte: " + column.typeName());
    }
    return switch (column.kind()) {
      case UUID -> "uniqueidentifier";
      case STRING -> type + "(" + textSize(type, column.size()) + ")";
      case DECIMAL ->
          "decimal(" + Math.max(1, Math.min(column.size(), 38)) + "," + column.scale() + ")";
      default -> type;
    };
  }

  /** O nome do tipo sem sufixos como " identity". */
  private static String baseName(ColumnInfo column) {
    var matcher = FIRST_WORD.matcher(column.typeName().toLowerCase(Locale.ROOT));
    return matcher.find() ? matcher.group() : "";
  }

  private static String textSize(String type, int size) {
    int limit = type.startsWith("n") ? 4000 : 8000;
    return size <= 0 || size > limit ? "max" : Integer.toString(size);
  }

  /** Tipo JDBC para parâmetros nulos (o driver precisa saber o tipo de um NULL). */
  static int jdbcType(ColumnInfo.Kind kind) {
    return switch (kind) {
      case STRING, UUID, XML, OTHER -> Types.NVARCHAR;
      case INTEGER -> Types.BIGINT;
      case DECIMAL -> Types.DECIMAL;
      case FLOAT -> Types.DOUBLE;
      case BOOLEAN -> Types.BIT;
      case DATE -> Types.DATE;
      case TIME -> Types.TIME;
      case DATE_TIME -> Types.TIMESTAMP;
      case DATE_TIME_OFFSET -> Types.TIMESTAMP_WITH_TIMEZONE;
      case BINARY -> Types.VARBINARY;
    };
  }
}
