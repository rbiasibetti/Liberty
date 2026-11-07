package br.com.liberty.infrastructure.security;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;

/**
 * Guarda as chaves no Vault KV v2, um campo por chave ({@code estado;criada;aposentada;pkcs8}), com
 * gravação condicional (check-and-set) para que só uma instância vença a rotação.
 */
public final class VaultSigningKeyStore implements SigningKeyStore {

  private static final String FIELD_PREFIX = "key.";

  private final VaultOperations vault;
  private final String dataPath;

  public VaultSigningKeyStore(VaultOperations vault, String mount, String secretPath) {
    this.vault = vault;
    this.dataPath = mount + "/data/" + secretPath;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Snapshot load() {
    VaultResponse response = vault.read(dataPath);
    if (response == null || response.getData() == null) {
      return new Snapshot(List.of(), 0);
    }
    Map<String, Object> envelope = response.getData();
    Map<String, Object> data = (Map<String, Object>) envelope.get("data");
    Map<String, Object> metadata = (Map<String, Object>) envelope.get("metadata");
    long version = metadata == null ? 0 : ((Number) metadata.get("version")).longValue();
    List<SigningKey> keys = new ArrayList<>();
    if (data != null) {
      data.forEach(
          (field, value) -> {
            if (field.startsWith(FIELD_PREFIX)) {
              keys.add(decode(field.substring(FIELD_PREFIX.length()), (String) value));
            }
          });
    }
    return new Snapshot(List.copyOf(keys), version);
  }

  @Override
  public boolean save(List<SigningKey> keys, long expectedVersion) {
    Map<String, Object> data = new LinkedHashMap<>();
    keys.forEach(key -> data.put(FIELD_PREFIX + key.kid(), encode(key)));
    try {
      vault.write(dataPath, Map.of("options", Map.of("cas", expectedVersion), "data", data));
      return true;
    } catch (VaultException e) {
      if (String.valueOf(e.getMessage()).contains("check-and-set")) {
        return false;
      }
      throw e;
    }
  }

  private static String encode(SigningKey key) {
    return key.state()
        + ";"
        + key.createdAt()
        + ";"
        + (key.retiredAt() == null ? "" : key.retiredAt())
        + ";"
        + Base64.getEncoder().encodeToString(key.privateKey().getEncoded());
  }

  private static SigningKey decode(String kid, String value) {
    String[] parts = value.split(";", -1);
    try {
      KeyFactory factory = KeyFactory.getInstance("RSA");
      PrivateKey privateKey =
          factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[3])));
      RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
      RSAPublicKey publicKey =
          (RSAPublicKey)
              factory.generatePublic(
                  new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
      return new SigningKey(
          kid,
          publicKey,
          crt,
          Instant.parse(parts[1]),
          SigningKey.State.valueOf(parts[0]),
          parts[2].isEmpty() ? null : Instant.parse(parts[2]));
    } catch (java.security.GeneralSecurityException | RuntimeException e) {
      throw new IllegalStateException("Chave de assinatura corrompida no cofre: " + kid, e);
    }
  }
}
