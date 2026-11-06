package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O provedor contra um Vault falso (KV v2 e login Kubernetes) em HTTP local. */
class VaultSecretProviderTest {

  private HttpServer server;
  private final List<String> requests = new ArrayList<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
    requests.add(exchange.getRequestMethod() + " " + path + " token=" + token);
    String body;
    int status = 200;
    if (path.equals("/v1/auth/kubernetes/login")) {
      body =
          "{\"auth\":{\"client_token\":\"k8s-token\",\"lease_duration\":3600,\"renewable\":true}}";
    } else if (!"vt".equals(token) && !"k8s-token".equals(token)) {
      status = 403;
      body = "{\"errors\":[\"permission denied\"]}";
    } else if (path.equals("/v1/secret/data/liberty/tenants/cliente-a/hml")) {
      body = "{\"data\":{\"data\":{\"password\":\"s3nha\"},\"metadata\":{\"version\":1}}}";
    } else if (path.equals("/v1/secret/data/liberty/tenants/sem-senha")) {
      body = "{\"data\":{\"data\":{\"outro\":\"x\"},\"metadata\":{\"version\":1}}}";
    } else {
      status = 404;
      body = "{\"errors\":[]}";
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private VaultSecretProvider provider(LibertyVaultProperties properties) {
    return new VaultSecretProvider(
        TenantPersistenceConfig.vaultTemplate(properties), properties.mount());
  }

  private LibertyVaultProperties tokenProperties(String token) {
    return new LibertyVaultProperties(
        uri(), "secret", LibertyVaultProperties.Authentication.TOKEN, token, null, null);
  }

  @Test
  void readsThePasswordWithTheConfiguredToken() {
    String password = provider(tokenProperties("vt")).password("liberty/tenants/cliente-a/hml");

    assertThat(password).isEqualTo("s3nha");
    assertThat(requests)
        .containsExactly("GET /v1/secret/data/liberty/tenants/cliente-a/hml token=vt");
  }

  @Test
  void logsInWithTheKubernetesServiceAccount(@TempDir Path dir) throws IOException {
    Path jwt = Files.writeString(dir.resolve("token"), "service-account-jwt");
    var properties =
        new LibertyVaultProperties(
            uri(),
            "secret",
            LibertyVaultProperties.Authentication.KUBERNETES,
            null,
            "liberty",
            jwt.toString());

    assertThat(provider(properties).password("liberty/tenants/cliente-a/hml")).isEqualTo("s3nha");
    assertThat(requests).contains("POST /v1/auth/kubernetes/login token=null");
    assertThat(requests.getLast()).endsWith("token=k8s-token");
  }

  @Test
  void missingSecretsAndFieldsBecomeUnavailable() {
    var provider = provider(tokenProperties("vt"));

    assertThatThrownBy(() -> provider.password("liberty/tenants/inexistente"))
        .isInstanceOf(TenantUnavailableException.class);
    assertThatThrownBy(() -> provider.password("liberty/tenants/sem-senha"))
        .isInstanceOf(TenantUnavailableException.class)
        .hasMessageContaining("sem senha");
  }

  @Test
  void wrongTokenOrDownServerBecomeUnavailableWithoutLeakingTheToken() {
    assertThatThrownBy(
            () -> provider(tokenProperties("errado")).password("liberty/tenants/cliente-a/hml"))
        .isInstanceOf(TenantUnavailableException.class)
        .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("errado"));

    server.stop(0);
    assertThatThrownBy(
            () -> provider(tokenProperties("vt")).password("liberty/tenants/cliente-a/hml"))
        .isInstanceOf(TenantUnavailableException.class);
  }

  @Test
  void referencesCannotEscapeTheSecretPath() {
    var provider = provider(tokenProperties("vt"));

    for (String bad :
        new String[] {
          null, "", "../sys/seal-status", "a/../b", "/abs", "a//b", "a b", "a?x=1", "a#b"
        }) {
      assertThatThrownBy(() -> provider.password(bad))
          .isInstanceOf(TenantUnavailableException.class);
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void propertiesDoNotPrintTheToken() {
    assertThat(tokenProperties("vt-secreto").toString()).doesNotContain("vt-secreto");
  }
}
