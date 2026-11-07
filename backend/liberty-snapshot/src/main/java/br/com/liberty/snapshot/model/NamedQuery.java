package br.com.liberty.snapshot.model;

import java.util.List;

/**
 * Consulta SELECT nomeada, revisada na publicação e chamada pelos scripts; os parâmetros são
 * nomeados.
 */
public record NamedQuery(String name, String sql, List<String> params) {

  public NamedQuery {
    params = params == null ? List.of() : List.copyOf(params);
  }
}
