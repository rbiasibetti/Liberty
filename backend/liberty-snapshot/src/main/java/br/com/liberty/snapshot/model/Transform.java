package br.com.liberty.snapshot.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Chamada a uma função de transformação da lista fechada (ver TransformSpecs). */
public record Transform(String fn, List<JsonNode> args) {

  public Transform {
    args = args == null ? List.of() : List.copyOf(args);
  }
}
