package br.com.liberty.engine.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Padrão de rota de uma integração: segmentos literais e variáveis {@code {nome}}, sem curingas.
 */
public final class RoutePattern {

  private record Segment(String text, boolean variable) {}

  private final List<Segment> segments;

  private RoutePattern(List<Segment> segments) {
    this.segments = segments;
  }

  public static RoutePattern parse(String route) {
    if (route == null || !route.startsWith("/") || route.length() == 1) {
      throw new IllegalArgumentException("Rota inválida");
    }
    List<Segment> parsed = new ArrayList<>();
    for (String part : route.substring(1).split("/", -1)) {
      if (part.isEmpty()) {
        throw new IllegalArgumentException("Rota com segmento vazio");
      }
      if (part.startsWith("{") && part.endsWith("}")) {
        parsed.add(new Segment(part.substring(1, part.length() - 1), true));
      } else {
        parsed.add(new Segment(part, false));
      }
    }
    return new RoutePattern(List.copyOf(parsed));
  }

  /** Variáveis do caminho, se os segmentos casam com o padrão. */
  public Optional<Map<String, String>> match(List<String> path) {
    if (path.size() != segments.size()) {
      return Optional.empty();
    }
    Map<String, String> variables = new HashMap<>();
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      String value = path.get(i);
      if (segment.variable()) {
        variables.put(segment.text(), value);
      } else if (!segment.text().equals(value)) {
        return Optional.empty();
      }
    }
    return Optional.of(variables);
  }

  /**
   * Estrutura sem os nomes das variáveis: {@code /a/{}/b}; duas rotas com a mesma estrutura são
   * ambíguas.
   */
  public String signature() {
    StringBuilder out = new StringBuilder();
    for (Segment segment : segments) {
      out.append('/').append(segment.variable() ? "{}" : segment.text());
    }
    return out.toString();
  }

  /**
   * Quanto mais à esquerda os literais, mais específico: usado para ordenar (literal vence
   * variável).
   */
  int[] specificity() {
    return segments.stream().mapToInt(s -> s.variable() ? 1 : 0).toArray();
  }
}
