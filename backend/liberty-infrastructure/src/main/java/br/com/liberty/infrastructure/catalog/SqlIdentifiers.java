package br.com.liberty.infrastructure.catalog;

import java.util.regex.Pattern;

/**
 * Único ponto onde um nome de tabela ou coluna vira texto de SQL. Os nomes vêm do catálogo do
 * tenant, não do cliente: a entrada do usuário só serve para localizar o objeto real, e o texto
 * devolvido é a grafia do catálogo entre colchetes. Valores de dados nunca passam por aqui (são
 * sempre parâmetros).
 */
public final class SqlIdentifiers {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

  private SqlIdentifiers() {}

  /** {@code [esquema].[tabela]} com a grafia do catálogo. */
  public static String table(TableInfo table) {
    return bracket(table.schema()) + "." + bracket(table.name());
  }

  /** {@code [coluna]} com a grafia do catálogo; falha se a coluna não existir na tabela. */
  public static String column(TableInfo table, String requested) {
    ColumnInfo column =
        table
            .column(requested)
            .orElseThrow(
                () -> new UnknownDatabaseObjectException("Coluna inexistente em " + table.name()));
    return bracket(column.name());
  }

  /** Nome seguro para exibição em mensagens: o próprio nome se for simples, senão um marcador. */
  public static String forMessage(String name) {
    return name != null && SAFE_NAME.matcher(name).matches() ? name : "<inválido>";
  }

  private static String bracket(String catalogName) {
    // Segunda barreira: mesmo vindo do catálogo, só nomes simples viram SQL.
    if (!SAFE_NAME.matcher(catalogName).matches()) {
      throw new InvalidIdentifierException("Nome fora do padrão simples no catálogo");
    }
    return "[" + catalogName + "]";
  }
}
