package br.com.liberty.infrastructure.tenancy;

import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;

/** Escreve a senha do tenant no Vault (KV v2, campo `password`); o valor nunca vai para log. */
public final class VaultSecretWriter implements SecretWriter {

  private static final Logger LOG = LoggerFactory.getLogger(VaultSecretWriter.class);
  private static final Pattern SAFE_REFERENCE = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*");

  private final VaultKeyValueOperations kv;

  public VaultSecretWriter(VaultOperations vault, String mount) {
    this.kv = vault.opsForKeyValue(mount, KeyValueBackend.KV_2);
  }

  @Override
  public void writePassword(String reference, String password) {
    if (reference == null || !SAFE_REFERENCE.matcher(reference).matches()) {
      throw new TenantUnavailableException("Referência de segredo inválida");
    }
    if (password == null || password.isEmpty() || password.length() > 256) {
      throw new TenantUnavailableException("Senha inválida");
    }
    try {
      kv.put(reference, Map.of("password", password));
    } catch (RuntimeException e) {
      LOG.warn("Falha ao gravar o segredo {}: {}", reference, e.getClass().getSimpleName());
      throw new TenantUnavailableException("Cofre indisponível para gravar o segredo", e);
    }
  }
}
