package br.com.liberty.domain.catalog;

/** O nome não tem a forma de um identificador simples; nunca chega ao banco. */
public class InvalidIdentifierException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidIdentifierException(String message) {
    super(message);
  }
}
