package br.com.liberty.infrastructure.tenancy;

/**
 * Cofre de segredos: devolve a senha do banco de um tenant a partir da referência guardada no core.
 */
public interface SecretProvider {

  /**
   * @throws TenantUnavailableException se o segredo não existir ou o cofre não responder
   */
  String password(String reference);
}
