package br.com.liberty.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Provedor OIDC falso (descoberta e JWKS) que também assina tokens de usuário para os testes. */
final class OidcStub implements AutoCloseable {

  private final HttpServer server;
  private final RSAKey key;
  final String issuer;

  OidcStub() throws IOException, JOSEException {
    this.key = new RSAKeyGenerator(2048).keyID("oidc-key").generate();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/liberty";
    server.createContext(
        "/realms/liberty/.well-known/openid-configuration",
        exchange ->
            write(
                exchange,
                "{\"issuer\":\""
                    + issuer
                    + "\",\"jwks_uri\":\""
                    + issuer
                    + "/protocol/openid-connect/certs\",\"subject_types_supported\":[\"public\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}"));
    server.createContext(
        "/realms/liberty/protocol/openid-connect/certs",
        exchange -> write(exchange, new JWKSet(key.toPublicJWK()).toString()));
    server.start();
  }

  private static void write(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /**
   * Token de usuário válido para o cliente da interface; {@code extra} sobrescreve ou acrescenta
   * claims.
   */
  String userToken(String sub, Map<String, Object> extra) {
    return sign(key, baseClaims(sub, issuer, "liberty-frontend", extra), "oidc-key");
  }

  String userToken(String sub) {
    return userToken(sub, Map.of());
  }

  String adminToken(String sub) {
    return userToken(
        sub, Map.of("realm_access", Map.of("roles", List.of("liberty-platform-admin"))));
  }

  static JWTClaimsSet baseClaims(String sub, String issuer, String azp, Map<String, Object> extra) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(sub)
            .claim("azp", azp)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)));
    extra.forEach(builder::claim);
    return builder.build();
  }

  static String sign(RSAKey signer, JWTClaimsSet claims, String kid) {
    try {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
      jwt.sign(new RSASSASigner(signer));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
