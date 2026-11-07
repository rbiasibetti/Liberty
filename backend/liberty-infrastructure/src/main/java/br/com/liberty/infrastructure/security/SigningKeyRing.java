package br.com.liberty.infrastructure.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conjunto de chaves de assinatura compartilhado entre instâncias: assina com a chave ACTIVE mais
 * nova, publica todas no JWKS e rotaciona por idade. Duas instâncias tentando rotacionar ao mesmo
 * tempo não geram duas chaves: a gravação condicional deixa só uma vencer.
 */
public final class SigningKeyRing {

  private static final Logger LOG = LoggerFactory.getLogger(SigningKeyRing.class);
  private static final int KEY_BITS = 3072;

  private final SigningKeyStore store;
  private final Clock clock;
  private final Duration rotationPeriod;
  private final Duration retirementGrace;
  private volatile List<SigningKey> keys = List.of();

  private final int keyBits;

  public SigningKeyRing(
      SigningKeyStore store, Clock clock, Duration rotationPeriod, Duration retirementGrace) {
    this(store, clock, rotationPeriod, retirementGrace, KEY_BITS);
  }

  /** Tamanho da chave configurável só para testes (gerar RSA 3072 repetidas vezes é lento). */
  SigningKeyRing(
      SigningKeyStore store,
      Clock clock,
      Duration rotationPeriod,
      Duration retirementGrace,
      int keyBits) {
    this.keyBits = keyBits;
    this.store = store;
    this.clock = clock;
    this.rotationPeriod = rotationPeriod;
    this.retirementGrace = retirementGrace;
  }

  /** Recarrega do cofre (as outras instâncias podem ter rotacionado). */
  public void refresh() {
    keys = store.load().keys();
  }

  /**
   * Cria a primeira chave ou rotaciona se a ativa passou do prazo; seguro para chamar em todas as
   * instâncias.
   */
  public void rotateIfDue() {
    for (int attempt = 0; attempt < 3; attempt++) {
      SigningKeyStore.Snapshot snapshot = store.load();
      Instant now = clock.instant();
      List<SigningKey> next = plan(snapshot.keys(), now);
      if (next == null) {
        keys = snapshot.keys();
        return;
      }
      if (store.save(next, snapshot.version())) {
        keys = next;
        LOG.info("Chaves de assinatura atualizadas: {}", next);
        return;
      }
      LOG.info("Outra instância rotacionou as chaves primeiro; recarregando");
    }
    refresh();
  }

  /** Nova lista de chaves, ou null se nada precisa mudar. */
  private List<SigningKey> plan(List<SigningKey> existing, Instant now) {
    List<SigningKey> kept = new ArrayList<>();
    boolean changed = false;
    for (SigningKey key : existing) {
      boolean expiredRetiring =
          key.state() == SigningKey.State.RETIRING
              && key.retiredAt().plus(retirementGrace).isBefore(now);
      if (expiredRetiring) {
        changed = true;
      } else {
        kept.add(key);
      }
    }
    SigningKey newest =
        kept.stream()
            .filter(k -> k.state() == SigningKey.State.ACTIVE)
            .max(Comparator.comparing(SigningKey::createdAt))
            .orElse(null);
    if (newest == null || newest.createdAt().plus(rotationPeriod).isBefore(now)) {
      List<SigningKey> result = new ArrayList<>();
      for (SigningKey key : kept) {
        result.add(key.state() == SigningKey.State.ACTIVE ? key.retire(now) : key);
      }
      result.add(generate(now, keyBits));
      return result;
    }
    return changed ? kept : null;
  }

  private static SigningKey generate(Instant now, int keyBits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(keyBits);
      KeyPair pair = generator.generateKeyPair();
      return new SigningKey(
          UUID.randomUUID().toString(),
          (RSAPublicKey) pair.getPublic(),
          (RSAPrivateCrtKey) pair.getPrivate(),
          now,
          SigningKey.State.ACTIVE,
          null);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Chave usada para assinar novos tokens: a ACTIVE mais recente. */
  public SigningKey current() {
    return keys.stream()
        .filter(k -> k.state() == SigningKey.State.ACTIVE)
        .max(Comparator.comparing(SigningKey::createdAt))
        .orElseThrow(() -> new IllegalStateException("Nenhuma chave de assinatura disponível"));
  }

  /**
   * JWKS público (chaves ACTIVE e RETIRING): é o que o endpoint publica e o que valida os tokens.
   */
  public JWKSet publicJwkSet() {
    List<com.nimbusds.jose.jwk.JWK> jwks = new ArrayList<>();
    for (SigningKey key : keys) {
      RSAKey jwk = key.toJwk(false);
      jwks.add(jwk);
    }
    return new JWKSet(jwks);
  }

  /** Só a chave atual, com a parte privada, para o codificador de tokens. */
  public JWKSet signingJwkSet() {
    return new JWKSet(current().toJwk(true));
  }
}
