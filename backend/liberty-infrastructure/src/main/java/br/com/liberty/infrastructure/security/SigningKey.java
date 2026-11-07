package br.com.liberty.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

/**
 * Chave RSA de assinatura dos tokens. ACTIVE assina e verifica; RETIRING só verifica até expirar.
 */
public record SigningKey(
    String kid,
    RSAPublicKey publicKey,
    RSAPrivateCrtKey privateKey,
    Instant createdAt,
    State state,
    Instant retiredAt) {

  public enum State {
    ACTIVE,
    RETIRING
  }

  public SigningKey retire(Instant now) {
    return new SigningKey(kid, publicKey, privateKey, createdAt, State.RETIRING, now);
  }

  public RSAKey toJwk(boolean includePrivate) {
    RSAKey.Builder builder =
        new RSAKey.Builder(publicKey)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256);
    if (includePrivate) {
      builder.privateKey(privateKey);
    }
    return builder.build();
  }

  @Override
  public String toString() {
    return "SigningKey[kid=" + kid + ", state=" + state + "]";
  }
}
