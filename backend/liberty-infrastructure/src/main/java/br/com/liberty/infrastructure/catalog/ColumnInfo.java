package br.com.liberty.infrastructure.catalog;

/** Coluna como o catálogo do banco a descreve. */
public record ColumnInfo(
    String name,
    String typeName,
    Kind kind,
    int size,
    int scale,
    boolean nullable,
    boolean hasDefault,
    boolean generated,
    int ordinal,
    int primaryKeyPosition) {

  /** Família do tipo, para validar e converter valores sem depender do nome do tipo no SGBD. */
  public enum Kind {
    STRING,
    INTEGER,
    DECIMAL,
    FLOAT,
    BOOLEAN,
    DATE,
    TIME,
    DATE_TIME,
    DATE_TIME_OFFSET,
    BINARY,
    UUID,
    XML,
    OTHER
  }

  public boolean primaryKey() {
    return primaryKeyPosition > 0;
  }

  /**
   * Gravável pelo motor: identidade, colunas calculadas e rowversion são geradas pelo banco e nunca
   * recebem valor do payload.
   */
  public boolean writable() {
    return !generated;
  }

  /** Obrigatória no INSERT: não aceita nulo, não tem valor padrão e não é gerada. */
  public boolean required() {
    return !nullable && !hasDefault && !generated;
  }
}
