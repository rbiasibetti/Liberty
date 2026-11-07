package br.com.liberty.infrastructure.tenancy;

/** Grava a senha do banco de um tenant no cofre; o core guarda só a referência. */
public interface SecretWriter {

  /**
   * @throws TenantUnavailableException se a referência for inválida ou o cofre não responder
   */
  void writePassword(String reference, String password);
}
