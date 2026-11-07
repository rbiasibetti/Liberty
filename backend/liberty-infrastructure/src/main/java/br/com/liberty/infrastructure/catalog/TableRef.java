package br.com.liberty.infrastructure.catalog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Referência sintática a uma tabela ou view: {@code tabela} (esquema dbo) ou {@code
 * esquema.tabela}. Só aceita identificadores simples ({@code [A-Za-z_][A-Za-z0-9_]{0,127}});
 * qualquer outra coisa, inclusive colchetes, aspas, espaços, pontos extras e Unicode, é recusada
 * antes de qualquer consulta ao banco. Existir na referência não basta: o nome ainda precisa
 * existir no catálogo do tenant.
 */
public record TableRef(String schema, String name) {

  public static final String DEFAULT_SCHEMA = "dbo";

  private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]{0,127}";
  private static final Pattern REFERENCE =
      Pattern.compile("(?:(" + IDENTIFIER + ")\\.)?(" + IDENTIFIER + ")");
  static final Pattern PLAIN_IDENTIFIER = Pattern.compile(IDENTIFIER);

  public static TableRef parse(String reference) {
    if (reference == null) {
      throw new InvalidIdentifierException("Referência de tabela ausente");
    }
    Matcher matcher = REFERENCE.matcher(reference);
    if (!matcher.matches()) {
      throw new InvalidIdentifierException("Referência de tabela inválida");
    }
    return new TableRef(
        matcher.group(1) == null ? DEFAULT_SCHEMA : matcher.group(1), matcher.group(2));
  }

  /**
   * Chave de comparação: o SQL Server compara identificadores sem diferenciar maiúsculas na maioria
   * dos bancos.
   */
  TableRef normalized() {
    return new TableRef(
        schema.toLowerCase(java.util.Locale.ROOT), name.toLowerCase(java.util.Locale.ROOT));
  }

  @Override
  public String toString() {
    return schema + "." + name;
  }
}
