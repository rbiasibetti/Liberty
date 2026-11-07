package br.com.liberty.engine.read;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.snapshot.model.Param;
import br.com.liberty.snapshot.model.Select;
import br.com.liberty.snapshot.model.Snapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Confere a entrada de uma leitura contra o que o snapshot declara: parâmetros desconhecidos,
 * repetidos, ausentes ou do tipo errado, e os limites de página. Tudo que é recusado vira uma lista
 * de violações, uma por parâmetro. O valor recebido nunca é repetido nas mensagens.
 */
final class InputParser {

  static final int DEFAULT_LIMIT = 100;
  static final int MAX_LIMIT = 1000;
  static final int MAX_OFFSET = 100_000;
  private static final int DEFAULT_MAX_LENGTH = 400;
  private static final int HARD_MAX_LENGTH = 4000;
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,18}");
  private static final Pattern SORT = Pattern.compile("-?[A-Za-z_][A-Za-z0-9_]{0,127}");

  private InputParser() {}

  static ParsedInput parse(
      Snapshot snapshot, Map<String, String> pathVariables, Map<String, List<String>> query) {
    Select select = snapshot.operation().select();
    List<Violation> violations = new ArrayList<>();
    Map<String, Param> declared = new HashMap<>();
    snapshot.request().params().forEach(p -> declared.put(p.name(), p));

    Set<String> allowed = new HashSet<>(List.of("limit", "offset", "sort"));
    declared.values().stream()
        .filter(p -> "query".equals(p.in()))
        .forEach(p -> allowed.add(p.name()));
    for (Map.Entry<String, List<String>> entry : query.entrySet()) {
      if (!allowed.contains(entry.getKey())) {
        violations.add(
            new Violation(
                safeName(entry.getKey()), "unknown_parameter", "Parâmetro não reconhecido"));
      } else if (entry.getValue().size() != 1) {
        violations.add(
            new Violation(
                entry.getKey(), "repeated_parameter", "Informe o parâmetro uma única vez"));
      }
    }

    Map<String, String> values = new HashMap<>();
    for (Param param : declared.values()) {
      String raw =
          "path".equals(param.in())
              ? pathVariables.get(param.name())
              : first(query.get(param.name()));
      if (raw == null) {
        if (param.isRequired()) {
          violations.add(new Violation(param.name(), "required", "Parâmetro obrigatório"));
        }
        continue;
      }
      declaredCheck(param, raw)
          .ifPresentOrElse(violations::add, () -> values.put(param.name(), raw));
    }

    int limit = intParam(query, "limit", defaultLimit(select), 1, maxLimit(select), violations);
    int offset = intParam(query, "offset", 0, 0, MAX_OFFSET, violations);
    String sort = first(query.get("sort"));
    if (sort != null && !SORT.matcher(sort).matches()) {
      violations.add(
          new Violation(
              "sort",
              "invalid_value",
              "Use o nome de uma coluna ordenável, com '-' na frente para decrescente"));
      sort = null;
    } else if (sort != null && !sortAllowed(select, sort)) {
      violations.add(
          new Violation("sort", "sort_not_allowed", "Coluna não ordenável nesta integração"));
      sort = null;
    }
    if (!violations.isEmpty()) {
      throw ProblemException.validation(violations);
    }
    return new ParsedInput(values, limit, offset, sort);
  }

  private static java.util.Optional<Violation> declaredCheck(Param param, String raw) {
    if (raw.isEmpty()) {
      return java.util.Optional.of(new Violation(param.name(), "empty_value", "Valor vazio"));
    }
    int maxLength =
        Math.min(
            param.maxLength() == null ? DEFAULT_MAX_LENGTH : param.maxLength(), HARD_MAX_LENGTH);
    if (raw.length() > maxLength) {
      return java.util.Optional.of(
          new Violation(param.name(), "too_long", "Valor maior que " + maxLength + " caracteres"));
    }
    boolean valid =
        switch (param.type()) {
          case "integer" -> INTEGER.matcher(raw).matches();
          case "number" -> isNumber(raw);
          case "boolean" -> raw.equals("true") || raw.equals("false");
          default -> true;
        };
    return valid
        ? java.util.Optional.empty()
        : java.util.Optional.of(
            new Violation(param.name(), "invalid_type", "Esperado: " + param.type()));
  }

  private static boolean isNumber(String raw) {
    if (raw.length() > 40) {
      return false;
    }
    try {
      new BigDecimal(raw);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static int intParam(
      Map<String, List<String>> query,
      String name,
      int fallback,
      int min,
      int max,
      List<Violation> violations) {
    String raw = first(query.get(name));
    if (raw == null) {
      return fallback;
    }
    if (!INTEGER.matcher(raw).matches()) {
      violations.add(new Violation(name, "invalid_type", "Esperado: inteiro"));
      return fallback;
    }
    long value = Long.parseLong(raw);
    if (value < min || value > max) {
      violations.add(
          new Violation(name, "out_of_range", "Valor fora do intervalo " + min + " a " + max));
      return fallback;
    }
    return (int) value;
  }

  private static boolean sortAllowed(Select select, String sort) {
    String name = sort.startsWith("-") ? sort.substring(1) : sort;
    return select.sortable().stream().anyMatch(column -> column.equalsIgnoreCase(name));
  }

  private static int defaultLimit(Select select) {
    Integer configured = select.pageSize() == null ? null : select.pageSize().defaultSize();
    return Math.min(configured == null ? DEFAULT_LIMIT : configured, maxLimit(select));
  }

  private static int maxLimit(Select select) {
    Integer configured = select.pageSize() == null ? null : select.pageSize().max();
    return Math.min(configured == null ? MAX_LIMIT : configured, MAX_LIMIT);
  }

  private static String first(List<String> values) {
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  /** Nome de parâmetro para mensagens: só se for simples; caso contrário um marcador. */
  private static String safeName(String name) {
    return name != null && SORT.matcher(name).matches() ? name : "<inválido>";
  }
}
