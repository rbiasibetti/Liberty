package br.com.liberty.engine.exports;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * URL assinada de download: {@code base64url(v1|tenant|job|expiração).base64url(HMAC-SHA256)}. O
 * tenant e o pedido vão dentro da parte assinada, então um link nunca serve para outro tenant ou
 * outro pedido, e vence sozinho.
 */
public final class ExportLinks {

  /** O que a assinatura garante. */
  public record Claims(String tenant, long jobId, Instant expires) {}

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] key;
  private final Clock clock;

  public ExportLinks(byte[] key, Clock clock) {
    if (key.length < 32) {
      throw new IllegalArgumentException("A chave das URLs assinadas precisa ter 32 bytes ou mais");
    }
    this.key = key.clone();
    this.clock = clock;
  }

  public String sign(String tenant, long jobId, Instant expires) {
    String payload = "v1|" + tenant + "|" + jobId + "|" + expires.getEpochSecond();
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(bytes));
  }

  /** Vazio para qualquer token malformado, adulterado ou vencido. */
  public Optional<Claims> verify(String token) {
    if (token == null || token.length() > 512) {
      return Optional.empty();
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot != token.lastIndexOf('.')) {
      return Optional.empty();
    }
    try {
      byte[] payload = DECODER.decode(token.substring(0, dot));
      byte[] given = DECODER.decode(token.substring(dot + 1));
      if (!MessageDigest.isEqual(mac(payload), given)) {
        return Optional.empty();
      }
      String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
      if (parts.length != 4 || !parts[0].equals("v1")) {
        return Optional.empty();
      }
      Instant expires = Instant.ofEpochSecond(Long.parseLong(parts[3]));
      if (!expires.isAfter(clock.instant())) {
        return Optional.empty();
      }
      return Optional.of(new Claims(parts[1], Long.parseLong(parts[2]), expires));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private byte[] mac(byte[] data) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
