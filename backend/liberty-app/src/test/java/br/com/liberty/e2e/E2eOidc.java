package br.com.liberty.e2e;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provedor OIDC falso só para o E2E: descoberta, JWKS, autorização (aprova sozinho, no usuário
 * escolhido em {@code /__user?sub=}) e troca de código com verificação de PKCE S256.
 */
final class E2eOidc implements AutoCloseable {

  private record Grant(String challenge, String nonce, String sub, String redirectUri) {}

  private final HttpServer server;
  private final RSAKey key;
  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final String clientId;
  final String issuer;
  private volatile String currentUser = "integrador";

  E2eOidc(int port, String clientId) throws Exception {
    this.clientId = clientId;
    this.key = new RSAKeyGenerator(2048).keyID("e2e-key").generate();
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    this.issuer = "http://127.0.0.1:" + port + "/realms/liberty";
    String base = issuer + "/protocol/openid-connect";
    server.createContext(
        "/realms/liberty/.well-known/openid-configuration",
        ex ->
            json(
                ex,
                200,
                "{\"issuer\":\""
                    + issuer
                    + "\",\"authorization_endpoint\":\""
                    + base
                    + "/auth\",\"token_endpoint\":\""
                    + base
                    + "/token\",\"jwks_uri\":\""
                    + base
                    + "/certs\",\"end_session_endpoint\":\""
                    + base
                    + "/logout\",\"response_types_supported\":[\"code\"],"
                    + "\"subject_types_supported\":[\"public\"],"
                    + "\"code_challenge_methods_supported\":[\"S256\"],"
                    + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}"));
    server.createContext(
        "/realms/liberty/protocol/openid-connect/certs",
        ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
    server.createContext("/realms/liberty/protocol/openid-connect/auth", this::authorize);
    server.createContext("/realms/liberty/protocol/openid-connect/token", this::token);
    server.createContext(
        "/realms/liberty/protocol/openid-connect/logout",
        ex -> {
          String target = query(ex).getOrDefault("post_logout_redirect_uri", "/");
          ex.getResponseHeaders().add("Location", target);
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.createContext(
        "/__user",
        ex -> {
          currentUser = query(ex).getOrDefault("sub", currentUser);
          json(ex, 200, "{\"sub\":\"" + currentUser + "\"}");
        });
  }

  void start() {
    server.start();
  }

  HttpServer server() {
    return server;
  }

  /** Token de usuário aceito pela API (mesmo formato do token de acesso do login). */
  String accessToken(String sub, List<String> realmRoles) {
    return sign(sub, null, Map.of("realm_access", Map.of("roles", realmRoles)));
  }

  private String sign(String sub, String nonce, Map<String, Object> extra) {
    try {
      Instant now = Instant.now();
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject(sub)
              .audience(clientId)
              .claim("azp", clientId)
              .claim("preferred_username", sub)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(3600)));
      if (nonce != null) {
        claims.claim("nonce", nonce);
      }
      extra.forEach(claims::claim);
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("e2e-key").build(), claims.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void authorize(HttpExchange ex) throws IOException {
    Map<String, String> q = query(ex);
    if (!"S256".equals(q.get("code_challenge_method")) || q.get("code_challenge") == null) {
      json(ex, 400, "{\"error\":\"pkce_required\"}");
      return;
    }
    String code = UUID.randomUUID().toString();
    grants.put(
        code,
        new Grant(q.get("code_challenge"), q.get("nonce"), currentUser, q.get("redirect_uri")));
    String sep = q.get("redirect_uri").contains("?") ? "&" : "?";
    ex.getResponseHeaders()
        .add(
            "Location",
            q.get("redirect_uri")
                + sep
                + "code="
                + code
                + "&state="
                + q.get("state")
                + "&iss="
                + issuer);
    ex.sendResponseHeaders(302, -1);
    ex.close();
  }

  private void token(HttpExchange ex) throws IOException {
    ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
    if ("OPTIONS".equals(ex.getRequestMethod())) {
      ex.sendResponseHeaders(204, -1);
      ex.close();
      return;
    }
    Map<String, String> form =
        parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    Grant grant = grants.remove(form.get("code"));
    if (grant == null || !"authorization_code".equals(form.get("grant_type"))) {
      json(ex, 400, "{\"error\":\"invalid_grant\"}");
      return;
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(form.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII));
      String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
      if (!expected.equals(grant.challenge())) {
        json(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE\"}");
        return;
      }
    } catch (Exception e) {
      throw new IOException(e);
    }
    Map<String, Object> body = new HashMap<>();
    body.put("access_token", sign(grant.sub(), null, Map.of()));
    body.put("id_token", sign(grant.sub(), grant.nonce(), Map.of()));
    body.put("token_type", "Bearer");
    body.put("expires_in", 3600);
    body.put("scope", "openid profile");
    json(ex, 200, com.nimbusds.jose.util.JSONObjectUtils.toJSONString(body));
  }

  private static Map<String, String> query(HttpExchange ex) {
    String raw = ex.getRequestURI().getRawQuery();
    return raw == null ? Map.of() : parse(raw);
  }

  private static Map<String, String> parse(String raw) {
    Map<String, String> out = new HashMap<>();
    for (String pair : raw.split("&")) {
      int i = pair.indexOf('=');
      if (i > 0) {
        out.put(
            URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  static void json(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
