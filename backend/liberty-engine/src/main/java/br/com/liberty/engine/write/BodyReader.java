package br.com.liberty.engine.write;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lê o corpo da requisição com limites rígidos: tamanho em bytes, profundidade, tamanho de texto e
 * número, chaves duplicadas e itens por array. O conteúdo recebido nunca é repetido nas mensagens.
 */
final class BodyReader {

  static final int MAX_ARRAY_ITEMS = 10_000;
  private static final int MAX_VIOLATIONS = 20;

  private static final JsonMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(1_000_000)
                          .maxNumberLength(50)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private BodyReader() {}

  static JsonMapper mapper() {
    return MAPPER;
  }

  static JsonNode read(byte[] body, int maxBytes) {
    if (body == null || body.length == 0) {
      throw new ProblemException(
          400, "body-required", "Corpo obrigatório", "Esta integração exige um corpo JSON");
    }
    if (body.length > maxBytes) {
      throw tooLarge();
    }
    JsonNode document;
    try {
      document = MAPPER.readTree(body);
    } catch (RuntimeException e) {
      throw new ProblemException(
          400,
          "invalid-json",
          "JSON inválido",
          "O corpo não é um JSON válido ou excede os limites");
    }
    List<Violation> violations = new ArrayList<>();
    checkArrays(document, violations);
    if (!violations.isEmpty()) {
      throw ProblemException.validation(violations);
    }
    return document;
  }

  static ProblemException tooLarge() {
    return new ProblemException(
        413, "payload-too-large", "Corpo grande demais", "O corpo excede o tamanho permitido");
  }

  /** Percorre sem recursão: arrays acima do limite são recusados. */
  private static void checkArrays(JsonNode root, List<Violation> violations) {
    Deque<Object[]> pending = new ArrayDeque<>();
    pending.push(new Object[] {root, ""});
    while (!pending.isEmpty() && violations.size() < MAX_VIOLATIONS) {
      Object[] entry = pending.pop();
      JsonNode node = (JsonNode) entry[0];
      String pointer = (String) entry[1];
      if (node.isArray()) {
        if (node.size() > MAX_ARRAY_ITEMS) {
          violations.add(
              new Violation(
                  pointer.isEmpty() ? "/" : pointer,
                  "too_many_items",
                  "No máximo " + MAX_ARRAY_ITEMS + " itens por lista"));
          continue;
        }
        for (int i = 0; i < node.size(); i++) {
          pending.push(new Object[] {node.get(i), pointer + "/" + i});
        }
      } else if (node.isObject()) {
        for (var property : node.properties()) {
          pending.push(
              new Object[] {property.getValue(), pointer + "/" + escape(property.getKey())});
        }
      }
    }
  }

  private static String escape(String name) {
    return name.replace("~", "~0").replace("/", "~1");
  }
}
