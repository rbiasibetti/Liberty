package br.com.liberty.snapshot.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Entrada: parâmetros declarados, schema do corpo (JSON Schema 2020-12) e limite de tamanho. */
public record Request(List<Param> params, JsonNode bodySchema, Integer maxBodyBytes) {

  public Request {
    params = params == null ? List.of() : List.copyOf(params);
  }
}
