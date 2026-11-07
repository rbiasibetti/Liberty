package br.com.liberty.snapshot.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Valor de uma coluna: vem de um ponteiro no corpo ({@code source}), de um parâmetro ou de uma
 * constante.
 */
public record Field(
    String column, String source, String param, JsonNode constant, List<Transform> transforms) {

  public Field {
    transforms = transforms == null ? List.of() : List.copyOf(transforms);
  }
}
