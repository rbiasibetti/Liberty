package br.com.liberty.snapshot;

import java.time.format.DateTimeFormatter;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Funções de transformação de campo aceitas (lista fechada, sem execução de código) e o formato de
 * seus argumentos. A implementação das funções é da E13; aqui só se garante que o snapshot as
 * declare direito.
 */
public final class TransformSpecs {

  private TransformSpecs() {}

  /** Mensagem de erro se a chamada for inválida, ou vazio se estiver correta. */
  public static Optional<String> check(String fn, JsonNode args) {
    int count = args == null || args.isNull() ? 0 : args.size();
    if (args != null && !args.isNull() && !args.isArray()) {
      return Optional.of("args deve ser uma lista");
    }
    return switch (fn) {
      case "trim", "upper", "lower", "digits" ->
          count == 0 ? Optional.empty() : Optional.of(fn + " não recebe argumentos");
      case "substring" -> substring(args, count);
      case "replace" -> replace(args, count);
      case "toDate" -> toDate(args, count);
      case "toDecimal" -> toDecimal(args, count);
      case "default" ->
          count == 1 && !args.get(0).isObject() && !args.get(0).isArray()
              ? Optional.empty()
              : Optional.of("default recebe um valor simples");
      case "map" -> map(args, count);
      default -> Optional.of("função desconhecida");
    };
  }

  private static Optional<String> substring(JsonNode args, int count) {
    if (count != 2 || !args.get(0).isInt() || !args.get(1).isInt()) {
      return Optional.of("substring recebe dois inteiros (início e fim)");
    }
    int start = args.get(0).asInt();
    int end = args.get(1).asInt();
    return start >= 0 && end >= start
        ? Optional.empty()
        : Optional.of("substring exige 0 <= início <= fim");
  }

  private static Optional<String> replace(JsonNode args, int count) {
    if (count != 2 || !args.get(0).isString() || !args.get(1).isString()) {
      return Optional.of("replace recebe dois textos (de e para)");
    }
    String from = args.get(0).asString();
    return !from.isEmpty() && from.length() <= 200 && args.get(1).asString().length() <= 200
        ? Optional.empty()
        : Optional.of("replace: 'de' não pode ser vazio e os textos têm no máximo 200 caracteres");
  }

  private static Optional<String> toDate(JsonNode args, int count) {
    if (count != 1 || !args.get(0).isString() || args.get(0).asString().length() > 50) {
      return Optional.of("toDate recebe um formato de data (até 50 caracteres)");
    }
    try {
      DateTimeFormatter.ofPattern(args.get(0).asString());
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.of("toDate: formato de data inválido");
    }
  }

  private static Optional<String> toDecimal(JsonNode args, int count) {
    return count == 1
            && args.get(0).isInt()
            && args.get(0).asInt() >= 0
            && args.get(0).asInt() <= 18
        ? Optional.empty()
        : Optional.of("toDecimal recebe a escala, um inteiro de 0 a 18");
  }

  private static Optional<String> map(JsonNode args, int count) {
    if (count != 1
        || !args.get(0).isObject()
        || args.get(0).size() == 0
        || args.get(0).size() > 100) {
      return Optional.of("map recebe um objeto de 1 a 100 pares");
    }
    for (JsonNode value : args.get(0).values()) {
      if (value.isObject() || value.isArray()) {
        return Optional.of("map só aceita valores simples");
      }
    }
    return Optional.empty();
  }
}
