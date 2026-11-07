package br.com.liberty.engine.write;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.util.List;

/**
 * Linha já validada e convertida, pronta para o banco; filhos alinhados a {@code plan.children}.
 */
record RowData(WritePlan plan, List<Cell> cells, List<List<RowData>> children) {

  /** Valor tipado de uma coluna ({@code null} é NULL explícito). */
  record Cell(ColumnInfo column, Object value) {}
}
