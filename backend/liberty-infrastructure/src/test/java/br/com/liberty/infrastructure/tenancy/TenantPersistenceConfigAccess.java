package br.com.liberty.infrastructure.tenancy;

import org.springframework.vault.core.VaultTemplate;

/** Ponte de teste para o auxiliar estático de pacote que monta o VaultTemplate. */
public final class TenantPersistenceConfigAccess {

  private TenantPersistenceConfigAccess() {}

  public static VaultTemplate vaultTemplate(LibertyVaultProperties properties) {
    return TenantPersistenceConfig.vaultTemplate(properties);
  }
}
