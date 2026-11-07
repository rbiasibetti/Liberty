package br.com.liberty.snapshot.model;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Caso de teste salvo na versão: entrada e o que se espera; todos precisam passar para publicar.
 */
public record TestCase(String name, TestRequest request, Expect expect) {

  public TestCase {
    request = request == null ? new TestRequest(Map.of(), null) : request;
  }

  public record TestRequest(Map<String, JsonNode> params, JsonNode body) {

    public TestRequest {
      params = params == null ? Map.of() : Map.copyOf(params);
    }
  }

  public record Expect(int status, JsonNode bodyContains) {}
}
