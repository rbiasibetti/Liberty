package br.com.liberty.infrastructure.catalog;

/**
 * A tabela, view ou coluna não existe (ou não é visível ao usuário do tenant) no banco do tenant.
 */
public class UnknownDatabaseObjectException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownDatabaseObjectException(String message) {
    super(message);
  }
}
