package br.com.liberty.infrastructure.tenancy;

import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

/** Lê a senha do banco do tenant no Vault (KV v2, campo `password`). */
public final class VaultSecretProvider implements SecretProvider {

  private static final Logger LOG = LoggerFactory.getLogger(VaultSecretProvider.class);
  private static final Pattern SAFE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");

  private final VaultKeyValueOperations kv;

  public VaultSecretProvider(VaultOperations vault, String mount) {
    this.kv = vault.opsForKeyValue(mount, KeyValueBackend.KV_2);
  }

  @Override
  public String password(String reference) {
    if (reference == null || !SAFE_REFERENCE.matcher(reference).matches()) {
      throw new TenantUnavailableException("Referência de segredo inválida");
    }
    try {
      VaultResponse response = kv.get(reference);
      Map<String, Object> data = response == null ? null : response.getData();
      Object password = data == null ? null : data.get("password");
      if (!(password instanceof String text) || text.isEmpty()) {
        throw new TenantUnavailableException("Segredo sem senha: " + reference);
      }
      return text;
    } catch (TenantUnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      LOG.warn("Falha ao ler o segredo {}: {}", reference, e.getClass().getSimpleName());
      throw new TenantUnavailableException("Segredo indisponível: " + reference, e);
    }
  }
}
