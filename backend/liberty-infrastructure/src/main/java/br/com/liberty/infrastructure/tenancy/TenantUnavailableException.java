package br.com.liberty.infrastructure.tenancy;

/**
 * O banco do tenant não pode ser usado agora (inexistente, inativo, segredo ou conexão
 * indisponível).
 */
public class TenantUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TenantUnavailableException(String message) {
    super(message);
  }

  public TenantUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
