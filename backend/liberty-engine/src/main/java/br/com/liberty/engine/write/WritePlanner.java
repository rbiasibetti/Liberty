package br.com.liberty.engine.write;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.SqlIdentifiers;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.catalog.UnknownDatabaseObjectException;
import br.com.liberty.snapshot.model.Field;
import br.com.liberty.snapshot.model.Node;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JsonPointer;

/**
 * Resolve a árvore do snapshot contra o catálogo do tenant e confere o que o banco sabe e o
 * snapshot não pode saber: tabela gravável, colunas existentes e graváveis, colunas obrigatórias
 * mapeadas, chave primária de tipo declarável. Defeitos aqui são do cadastro da integração (ou do
 * schema do cliente que mudou), nunca da requisição.
 */
final class WritePlanner {

  /** A integração não combina com o banco do cliente. */
  static final class ConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
      super(message);
    }
  }

  private final TenantCatalog catalog;

  WritePlanner(TenantCatalog catalog) {
    this.catalog = catalog;
  }

  /**
   * @throws UnknownDatabaseObjectException se o snapshot cita um objeto que não existe mais
   */
  WritePlan plan(TenantScope scope, Node root, String operationType) {
    return node(scope, root, operationType, true);
  }

  private WritePlan node(TenantScope scope, Node node, String operationType, boolean root) {
    TableInfo table = catalog.table(scope, node.table());
    if (!table.writable()) {
      throw new ConfigurationException("Tabela somente leitura: " + table.name());
    }
    boolean inserts = "INSERT".equals(operationType) || "UPSERT".equals(operationType);
    Set<String> mapped = new HashSet<>();
    List<WritePlan.FieldPlan> fields = new ArrayList<>();
    for (Field field : node.fields()) {
      ColumnInfo column = column(table, field.column());
      if (!column.writable()) {
        throw new ConfigurationException(
            "Coluna gerada pelo banco não pode ser mapeada: " + column.name());
      }
      mapped.add(column.name().toLowerCase(Locale.ROOT));
      fields.add(
          new WritePlan.FieldPlan(
              field, column, field.source() == null ? null : JsonPointer.compile(field.source())));
    }
    ColumnInfo foreignKey = node.foreignKey() == null ? null : column(table, node.foreignKey());
    if (foreignKey != null) {
      if (!foreignKey.writable()) {
        throw new ConfigurationException("Chave estrangeira gerada: " + foreignKey.name());
      }
      mapped.add(foreignKey.name().toLowerCase(Locale.ROOT));
    }
    if (inserts) {
      for (ColumnInfo column : table.columns()) {
        if (column.required() && !mapped.contains(column.name().toLowerCase(Locale.ROOT))) {
          throw new ConfigurationException(
              "Coluna obrigatória sem mapeamento: " + table.name() + "." + column.name());
        }
      }
    }
    ColumnInfo primaryKey = node.primaryKey() == null ? null : column(table, node.primaryKey());
    if (primaryKey != null && (root || !node.children().isEmpty())) {
      SqlTypes.declaration(primaryKey); // recusa já aqui um tipo que o motor não sabe capturar
    }
    List<ColumnInfo> keys = new ArrayList<>();
    for (String key : node.keyColumns()) {
      keys.add(column(table, key));
    }
    checkFullKey(table, keys);
    List<WritePlan> children = new ArrayList<>();
    for (Node child : node.children()) {
      children.add(node(scope, child, operationType, false));
    }
    return new WritePlan(
        node.table(),
        table,
        node.arrayPath(),
        node.arrayPath() == null ? null : JsonPointer.compile(node.arrayPath()),
        primaryKey,
        foreignKey,
        List.copyOf(keys),
        List.copyOf(fields),
        List.copyOf(children));
  }

  /** UPDATE e UPSERT miram uma linha: a chave tem de cobrir a chave primária da tabela. */
  private static void checkFullKey(TableInfo table, List<ColumnInfo> keys) {
    if (keys.isEmpty()) {
      return;
    }
    Set<String> given = new HashSet<>();
    keys.forEach(k -> given.add(k.name().toLowerCase(Locale.ROOT)));
    for (ColumnInfo pk : table.primaryKey()) {
      if (!given.contains(pk.name().toLowerCase(Locale.ROOT))) {
        throw new ConfigurationException(
            "keyColumns não cobre a chave primária de " + SqlIdentifiers.forMessage(table.name()));
      }
    }
  }

  private static ColumnInfo column(TableInfo table, String requested) {
    return table
        .column(requested)
        .orElseThrow(
            () -> new UnknownDatabaseObjectException("Coluna inexistente em " + table.name()));
  }
}
