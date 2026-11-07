package br.com.liberty.engine.read;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Converte o texto recebido para o tipo que a coluna do banco espera, para o valor ir ao SQL já
 * tipado (nunca como texto para o banco converter, o que perderia índices e abriria espaço a
 * surpresas).
 */
final class ValueBinder {

  /** O texto não é um valor válido para a coluna. */
  static final class InvalidValueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidValueException(String message) {
      super(message);
    }
  }

  /**
   * O tipo da coluna não suporta o uso pedido (ex.: comparar XML); é erro de configuração da
   * integração.
   */
  static final class UnsupportedColumnException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnsupportedColumnException(String message) {
      super(message);
    }
  }

  private ValueBinder() {}

  static Object bind(ColumnInfo column, String raw) {
    return bind(column, raw, false);
  }

  /**
   * Como {@link #bind(ColumnInfo, String)}; com {@code endOfDay}, uma data sem hora numa coluna de
   * data e hora vale o último instante do dia (limite superior inclusivo: "até 2026-01-07" inclui o
   * dia 7 inteiro).
   */
  static Object bind(ColumnInfo column, String raw, boolean endOfDay) {
    try {
      return switch (column.kind()) {
        case STRING -> raw;
        case INTEGER -> toLong(raw);
        case DECIMAL -> new BigDecimal(raw);
        case FLOAT -> toDouble(raw);
        case BOOLEAN -> toBoolean(raw);
        case DATE -> LocalDate.parse(raw);
        case TIME -> LocalTime.parse(raw);
        case DATE_TIME -> dateTime(raw, endOfDay);
        case DATE_TIME_OFFSET -> OffsetDateTime.parse(raw);
        case UUID -> UUID.fromString(raw);
        case BINARY, XML, OTHER ->
            throw new UnsupportedColumnException(
                "Tipo de coluna sem suporte a filtro: " + column.typeName());
      };
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw new InvalidValueException(
          "Valor inválido para o tipo " + column.kind().name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  private static LocalDateTime dateTime(String raw, boolean endOfDay) {
    if (raw.length() != 10) {
      return LocalDateTime.parse(raw);
    }
    LocalDate date = LocalDate.parse(raw);
    return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
  }

  private static Long toLong(String raw) {
    BigInteger value = new BigInteger(raw);
    if (value.bitLength() > 63) {
      throw new NumberFormatException("fora do intervalo");
    }
    return value.longValue();
  }

  private static Double toDouble(String raw) {
    double value = Double.parseDouble(raw);
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new NumberFormatException("não finito");
    }
    return value;
  }

  private static Boolean toBoolean(String raw) {
    return switch (raw) {
      case "true", "1" -> Boolean.TRUE;
      case "false", "0" -> Boolean.FALSE;
      default -> throw new IllegalArgumentException("booleano inválido");
    };
  }
}
