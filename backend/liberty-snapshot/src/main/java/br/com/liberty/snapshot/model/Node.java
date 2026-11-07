package br.com.liberty.snapshot.model;

import java.util.List;

/**
 * Nó da árvore de mapeamento. O raiz mapeia o corpo; cada filho mapeia um array ({@code arrayPath},
 * JSON Pointer relativo ao item do pai) e recebe do motor a chave estrangeira apontando para o pai.
 */
public record Node(
    String table,
    String arrayPath,
    String primaryKey,
    String foreignKey,
    List<String> keyColumns,
    List<Field> fields,
    List<Node> children) {

  public Node {
    keyColumns = keyColumns == null ? List.of() : List.copyOf(keyColumns);
    fields = List.copyOf(fields);
    children = children == null ? List.of() : List.copyOf(children);
  }
}
