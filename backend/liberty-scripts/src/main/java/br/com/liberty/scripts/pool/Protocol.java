package br.com.liberty.scripts.pool;

import br.com.liberty.scripts.ScriptExecutor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mensagens JSON-lines (uma por linha, versão 1) entre o pai e o trabalhador. */
public final class Protocol {

  public static final int VERSION = 1;
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Protocol() {}

  public static String write(JsonNode node) {
    return MAPPER.writeValueAsString(node);
  }

  public static JsonNode read(String line) {
    return MAPPER.readTree(line);
  }

  public static ObjectNode ready() {
    return MAPPER.createObjectNode().put("type", "ready").put("v", VERSION);
  }

  public static ObjectNode run(long id, ScriptExecutor.Request request) {
    return MAPPER
        .createObjectNode()
        .put("type", "run")
        .put("v", VERSION)
        .put("id", id)
        .put("script", request.script())
        .put("function", request.function())
        .put("input", request.inputJson())
        .put("timeoutMs", request.timeout().toMillis())
        .put("maxStatements", request.maxStatements());
  }

  public static ObjectNode call(String op, String[] params) {
    ObjectNode call = MAPPER.createObjectNode().put("type", "call").put("op", op);
    var array = call.putArray("params");
    for (String param : params) {
      array.add(param);
    }
    return call;
  }

  public static ObjectNode callResult(boolean ok, String value, String error) {
    ObjectNode node = MAPPER.createObjectNode().put("type", "callResult").put("ok", ok);
    if (value != null) {
      node.put("value", value);
    }
    if (error != null) {
      node.put("error", error);
    }
    return node;
  }

  public static ObjectNode result(long id, ScriptExecutor.Outcome outcome) {
    ObjectNode node =
        MAPPER
            .createObjectNode()
            .put("type", "result")
            .put("id", id)
            .put("status", outcome.status().name())
            .put("httpStatus", outcome.httpStatus())
            .put("logs", outcome.logs())
            .put("millis", outcome.millis());
    if (outcome.json() != null) {
      node.put("json", outcome.json());
    }
    if (outcome.message() != null) {
      node.put("message", outcome.message());
    }
    return node;
  }
}
