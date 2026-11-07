package br.com.liberty.engine.write;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.snapshot.model.Field;
import java.util.List;
import tools.jackson.core.JsonPointer;

/** Árvore de escrita resolvida contra o catálogo do tenant: tabelas e colunas reais. */
record WritePlan(
    String tableReference,
    TableInfo table,
    String arrayPath,
    JsonPointer arrayPointer,
    ColumnInfo primaryKey,
    ColumnInfo foreignKey,
    List<ColumnInfo> keyColumns,
    List<FieldPlan> fields,
    List<WritePlan> children) {

  /** Campo do snapshot ligado à coluna real e com o ponteiro já compilado. */
  record FieldPlan(Field field, ColumnInfo column, JsonPointer pointer) {

    /** Onde apontar o defeito deste campo numa resposta de validação. */
    String violationPath(String itemPointer) {
      if (field.source() != null) {
        return itemPointer + field.source();
      }
      return field.param() != null ? field.param() : column.name();
    }
  }

  boolean hasChildren() {
    return !children.isEmpty();
  }
}
