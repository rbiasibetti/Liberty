package br.com.liberty.snapshot.model;

import java.util.List;

/**
 * Leitura paginada de uma tabela ou view, com filtros e ordenação declarados (nunca vindos do
 * cliente).
 */
public record Select(
    String table,
    List<String> columns,
    List<Filter> filters,
    List<String> sortable,
    Sort defaultSort,
    PageSize pageSize,
    Boolean single) {

  public Select {
    columns = List.copyOf(columns);
    filters = filters == null ? List.of() : List.copyOf(filters);
    sortable = sortable == null ? List.of() : List.copyOf(sortable);
  }

  /** Filtro: um parâmetro declarado compara com uma coluna usando um operador da lista fechada. */
  public record Filter(String param, String column, String operator, Boolean required) {}

  public record Sort(String column, String direction) {}

  public record PageSize(
      @com.fasterxml.jackson.annotation.JsonProperty("default") Integer defaultSize, Integer max) {}
}
