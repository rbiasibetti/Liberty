package br.com.liberty.infrastructure.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tabela ou view do tenant com suas colunas, exatamente como o catálogo as reporta (grafia
 * incluída).
 */
public record TableInfo(String schema, String name, Kind kind, List<ColumnInfo> columns) {

  public enum Kind {
    TABLE,
    VIEW
  }

  public TableInfo {
    columns = List.copyOf(columns);
  }

  /** Coluna pelo nome: a grafia exata vence; senão vale a única que difere só em maiúsculas. */
  public Optional<ColumnInfo> column(String requested) {
    if (requested == null) {
      return Optional.empty();
    }
    Map<String, ColumnInfo> exact = new HashMap<>();
    ColumnInfo folded = null;
    int foldedMatches = 0;
    for (ColumnInfo column : columns) {
      exact.put(column.name(), column);
      if (column.name().equalsIgnoreCase(requested)) {
        folded = column;
        foldedMatches++;
      }
    }
    if (exact.containsKey(requested)) {
      return Optional.of(exact.get(requested));
    }
    return foldedMatches == 1 ? Optional.of(folded) : Optional.empty();
  }

  public List<ColumnInfo> primaryKey() {
    return columns.stream()
        .filter(ColumnInfo::primaryKey)
        .sorted(java.util.Comparator.comparingInt(ColumnInfo::primaryKeyPosition))
        .toList();
  }

  /** Só tabelas aceitam escrita; views são somente leitura para o motor. */
  public boolean writable() {
    return kind == Kind.TABLE;
  }
}
