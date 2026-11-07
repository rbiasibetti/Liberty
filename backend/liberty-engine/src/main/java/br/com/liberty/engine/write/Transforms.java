package br.com.liberty.engine.write;

import br.com.liberty.snapshot.model.Transform;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Funções de transformação de campo (lista fechada: sem execução de código). Os argumentos já foram
 * conferidos na publicação (TransformSpecs). Valores nulos passam direto, exceto em {@code
 * default}. Falhas de conversão viram {@link TransformException}, que a validação transforma em
 * violação do campo; nenhuma mensagem repete o valor recebido.
 */
public final class Transforms implements FieldTransformer {

  @Override
  public String apply(String value, List<Transform> transforms) {
    String current = value;
    for (Transform transform : transforms) {
      current = one(current, transform);
    }
    return current;
  }

  private static String one(String value, Transform transform) {
    List<JsonNode> args = transform.args();
    if ("default".equals(transform.fn())) {
      return value == null || value.isEmpty() ? text(args.getFirst()) : value;
    }
    if (value == null) {
      return null;
    }
    return switch (transform.fn()) {
      case "trim" -> value.strip();
      case "upper" -> value.toUpperCase(Locale.ROOT);
      case "lower" -> value.toLowerCase(Locale.ROOT);
      case "digits" -> value.replaceAll("\\D", "");
      case "substring" -> substring(value, args.get(0).asInt(), args.get(1).asInt());
      case "replace" -> value.replace(args.get(0).asString(), args.get(1).asString());
      case "toDate" -> toDate(value, args.getFirst().asString());
      case "toDecimal" -> toDecimal(value, args.getFirst().asInt());
      case "map" -> map(value, args.getFirst());
      default -> throw new IllegalStateException("Função desconhecida: " + transform.fn());
    };
  }

  private static String substring(String value, int start, int end) {
    int to = Math.min(end, value.length());
    int from = Math.min(start, to);
    return value.substring(from, to);
  }

  private static String toDate(String value, String pattern) {
    try {
      return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ROOT)).toString();
    } catch (DateTimeParseException e) {
      throw new TransformException("Data fora do formato esperado");
    }
  }

  private static String toDecimal(String value, int scale) {
    try {
      return new BigDecimal(value.strip()).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    } catch (NumberFormatException e) {
      throw new TransformException("Número inválido");
    }
  }

  private static String map(String value, JsonNode table) {
    JsonNode mapped = table.get(value);
    if (mapped == null) {
      throw new TransformException("Valor sem correspondência na tabela de conversão");
    }
    return text(mapped);
  }

  private static String text(JsonNode node) {
    if (node.isNull()) {
      return null;
    }
    return node.isNumber() ? node.decimalValue().toPlainString() : node.asString();
  }
}
