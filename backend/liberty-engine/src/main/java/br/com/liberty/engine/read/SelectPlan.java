package br.com.liberty.engine.read;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.util.List;
import java.util.Map;

/**
 * SELECT pronto para executar: o texto só contém identificadores do catálogo entre colchetes e
 * marcadores {@code :pN}; todos os valores do usuário estão em {@code params}. {@code fetch} é
 * {@code limit + 1} (a linha extra revela se há mais páginas).
 */
public record SelectPlan(
    String sql,
    Map<String, Object> params,
    List<ColumnInfo> columns,
    int limit,
    int offset,
    boolean single) {

  public SelectPlan {
    params = Map.copyOf(params);
    columns = List.copyOf(columns);
  }
}
