package br.com.liberty.infrastructure.tenancy;

/**
 * Dados de conexão de um tenant em um ambiente; a senha nunca está aqui, só a referência no cofre.
 */
public record TenantConnectionInfo(String jdbcUrl, String username, String secretRef, int poolMax) {

  @Override
  public String toString() {
    return "TenantConnectionInfo[secretRef=" + secretRef + ", poolMax=" + poolMax + "]";
  }
}
