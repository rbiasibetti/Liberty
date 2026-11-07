package br.com.liberty.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.SimpleSessionManager;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

/** O armazenamento contra um Vault KV v2 falso com gravação condicional (cas) e versões. */
class VaultSigningKeyStoreTest {

  private HttpServer server;
  private Map<String, Object> data;
  private int version;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/secret/data/liberty/jwt/keys", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @SuppressWarnings("unchecked")
  private void handle(HttpExchange exchange) throws IOException {
    if (!"t".equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"))) {
      reply(exchange, 403, "{\"errors\":[\"permission denied\"]}");
      return;
    }
    if ("GET".equals(exchange.getRequestMethod())) {
      if (data == null) {
        reply(exchange, 404, "{\"errors\":[]}");
        return;
      }
      String fields = JSONObjectUtils.toJSONString(data);
      reply(
          exchange,
          200,
          "{\"data\":{\"data\":" + fields + ",\"metadata\":{\"version\":" + version + "}}}");
      return;
    }
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    try {
      Map<String, Object> json = JSONObjectUtils.parse(body);
      long cas = ((Number) ((Map<String, Object>) json.get("options")).get("cas")).longValue();
      if (cas != version) {
        reply(
            exchange,
            400,
            "{\"errors\":[\"check-and-set parameter did not match the current version\"]}");
        return;
      }
      data = new LinkedHashMap<>((Map<String, Object>) json.get("data"));
      version++;
      reply(exchange, 200, "{\"data\":{\"version\":" + version + "}}");
    } catch (java.text.ParseException e) {
      reply(exchange, 400, "{\"errors\":[\"json\"]}");
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private VaultSigningKeyStore store(String token) {
    VaultEndpoint endpoint =
        VaultEndpoint.from(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    VaultClient client = VaultClient.builder().endpoint(endpoint).build();
    var template =
        new VaultTemplate(client, new SimpleSessionManager(new TokenAuthentication(token)));
    return new VaultSigningKeyStore(template, "secret", "liberty/jwt/keys");
  }

  private static SigningKey key(String kid, SigningKey.State state, Instant retiredAt)
      throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    return new SigningKey(
        kid,
        (RSAPublicKey) pair.getPublic(),
        (RSAPrivateCrtKey) pair.getPrivate(),
        Instant.parse("2026-11-06T12:00:00Z"),
        state,
        retiredAt);
  }

  @Test
  void anEmptyVaultIsVersionZero() {
    var snapshot = store("t").load();

    assertThat(snapshot.keys()).isEmpty();
    assertThat(snapshot.version()).isZero();
  }

  @Test
  void keysRoundTripWithTheirStateAndTimestamps() throws Exception {
    SigningKey active = key("k-active", SigningKey.State.ACTIVE, null);
    SigningKey retiring =
        key("k-old", SigningKey.State.RETIRING, Instant.parse("2026-11-05T01:02:03Z"));
    var store = store("t");

    assertThat(store.save(List.of(active, retiring), 0)).isTrue();
    var loaded = store.load();

    assertThat(loaded.version()).isEqualTo(1);
    assertThat(loaded.keys()).hasSize(2);
    SigningKey a =
        loaded.keys().stream().filter(k -> k.kid().equals("k-active")).findFirst().orElseThrow();
    SigningKey r =
        loaded.keys().stream().filter(k -> k.kid().equals("k-old")).findFirst().orElseThrow();
    assertThat(a.state()).isEqualTo(SigningKey.State.ACTIVE);
    assertThat(a.retiredAt()).isNull();
    assertThat(a.createdAt()).isEqualTo(Instant.parse("2026-11-06T12:00:00Z"));
    assertThat(a.privateKey().getEncoded()).isEqualTo(active.privateKey().getEncoded());
    assertThat(a.publicKey()).isEqualTo(active.publicKey());
    assertThat(r.state()).isEqualTo(SigningKey.State.RETIRING);
    assertThat(r.retiredAt()).isEqualTo(Instant.parse("2026-11-05T01:02:03Z"));
  }

  @Test
  void aStaleWriterLosesTheCheckAndSet() throws Exception {
    var store = store("t");
    SigningKey k = key("k1", SigningKey.State.ACTIVE, null);

    assertThat(store.save(List.of(k), 0)).isTrue();
    assertThat(store.save(List.of(k), 0)).isFalse();
    assertThat(store.save(List.of(k), 1)).isTrue();
    assertThat(store.load().version()).isEqualTo(2);
  }

  @Test
  void otherVaultErrorsAreNotSwallowed() throws Exception {
    SigningKey k = key("k1", SigningKey.State.ACTIVE, null);
    var wrongToken = store("errado");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrongToken.save(List.of(k), 0))
        .isInstanceOf(RuntimeException.class);
  }
}
