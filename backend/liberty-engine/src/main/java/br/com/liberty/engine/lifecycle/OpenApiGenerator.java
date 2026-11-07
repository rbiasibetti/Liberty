package br.com.liberty.engine.lifecycle;

import br.com.liberty.snapshot.model.Param;
import br.com.liberty.snapshot.model.Snapshot;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gera o OpenAPI 3.1 das integrações publicadas de um tenant e ambiente, a partir dos snapshots.
 */
public final class OpenApiGenerator {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private OpenApiGenerator() {}

  public static ObjectNode generate(String slug, String environment, List<Snapshot> snapshots) {
    ObjectNode doc = MAPPER.createObjectNode();
    doc.put("openapi", "3.1.0");
    ObjectNode info = doc.putObject("info");
    info.put("title", "Liberty - " + slug + " (" + environment + ")");
    info.put("version", "1");
    ObjectNode paths = doc.putObject("paths");
    ObjectNode scopes = MAPPER.createObjectNode();
    for (Snapshot snapshot : snapshots) {
      String key = snapshot.integration().key();
      scopes.put("integration:execute:" + key, snapshot.integration().name());
      String path = "/" + slug + "/api" + snapshot.integration().route();
      ObjectNode item = paths.has(path) ? (ObjectNode) paths.get(path) : paths.putObject(path);
      item.set(
          snapshot.integration().method().toLowerCase(java.util.Locale.ROOT), operation(snapshot));
    }
    ObjectNode components = doc.putObject("components");
    ObjectNode schemes = components.putObject("securitySchemes");
    ObjectNode oauth = schemes.putObject("oauth2").put("type", "oauth2");
    ObjectNode flow = oauth.putObject("flows").putObject("clientCredentials");
    flow.put("tokenUrl", "/oauth2/token");
    flow.set("scopes", scopes);
    ObjectNode problem = components.putObject("schemas").putObject("Problem");
    problem.put("type", "object");
    ObjectNode props = problem.putObject("properties");
    for (String name : List.of("type", "title", "detail", "requestId", "instance")) {
      props.putObject(name).put("type", "string");
    }
    props.putObject("status").put("type", "integer");
    props.putObject("errors").put("type", "array");
    return doc;
  }

  private static ObjectNode operation(Snapshot snapshot) {
    ObjectNode op = MAPPER.createObjectNode();
    op.put("operationId", snapshot.integration().key());
    op.put("summary", snapshot.integration().name());
    String type = snapshot.operation().type();
    ArrayNode parameters = op.putArray("parameters");
    for (Param param : snapshot.request().params()) {
      ObjectNode node = parameters.addObject();
      node.put("name", param.name()).put("in", param.in()).put("required", param.isRequired());
      node.putObject("schema").put("type", param.type());
    }
    if ("SELECT".equals(type)) {
      for (String name : List.of("limit", "offset")) {
        ObjectNode node = parameters.addObject();
        node.put("name", name).put("in", "query").put("required", false);
        node.putObject("schema").put("type", "integer");
      }
      ObjectNode sort = parameters.addObject();
      sort.put("name", "sort").put("in", "query").put("required", false);
      sort.putObject("schema").put("type", "string");
    } else {
      ObjectNode key = parameters.addObject();
      key.put("name", "Idempotency-Key").put("in", "header");
      key.put("required", snapshot.idempotency().required());
      key.putObject("schema").put("type", "string").put("maxLength", 200);
      JsonNode schema = snapshot.request().bodySchema();
      ObjectNode body = op.putObject("requestBody").put("required", true);
      ObjectNode media = body.putObject("content").putObject("application/json");
      media.set(
          "schema", schema == null ? MAPPER.createObjectNode().put("type", "object") : schema);
    }
    ObjectNode responses = op.putObject("responses");
    String success = successStatus(snapshot);
    ObjectNode ok = responses.putObject(success);
    ok.put("description", "Sucesso");
    ok.putObject("content").putObject("application/json").putObject("schema").put("type", "object");
    for (String status : problems(type)) {
      ObjectNode response = responses.putObject(status).put("description", "Erro");
      response
          .putObject("content")
          .putObject("application/problem+json")
          .putObject("schema")
          .put("$ref", "#/components/schemas/Problem");
    }
    op.putArray("security")
        .addObject()
        .putArray("oauth2")
        .add("integration:execute:" + snapshot.integration().key());
    return op;
  }

  private static String successStatus(Snapshot snapshot) {
    Integer configured = snapshot.response().successStatus();
    if (configured != null) {
      return Integer.toString(configured);
    }
    return switch (snapshot.operation().type()) {
      case "INSERT" -> "201";
      default -> "200";
    };
  }

  private static List<String> problems(String type) {
    return "SELECT".equals(type)
        ? List.of("400", "401", "403", "404", "429", "503", "504")
        : List.of("400", "401", "403", "404", "409", "413", "415", "422", "429", "503", "504");
  }
}
