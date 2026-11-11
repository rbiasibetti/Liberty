package br.com.liberty.e2e;

import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Pilha local do teste de ponta a ponta (Playwright): API real sobre banco central e banco de
 * cliente descartáveis, com OIDC (PKCE) e Vault falsos. Só roda com {@code -Dliberty.e2e=true};
 * fica no ar até o arquivo {@code target/e2e.stop} aparecer. Usuários: {@code integrador}, {@code
 * aprovador} e {@code admin}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {"server.port=18080"})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "liberty.e2e", matches = "true")
class E2eStackRunner {

  private static final int OIDC_PORT = 18081;
  private static final CoreTestDatabase.Scratch CORE = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch TENANT = CoreTestDatabase.create();
  private static final Map<String, String> VAULT = new ConcurrentHashMap<>();
  private static E2eOidc oidc;
  private static HttpServer vault;
  private static volatile boolean ready;

  static {
    try {
      oidc = new E2eOidc(OIDC_PORT, "liberty-frontend");
      oidc.server()
          .createContext(
              "/__ready", ex -> E2eOidc.json(ex, ready ? 200 : 503, "{\"ready\":" + ready + "}"));
      oidc.start();
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/",
          exchange -> {
            String ref = exchange.getRequestURI().getPath().substring("/v1/secret/data/".length());
            if (exchange.getRequestMethod().equals("GET")) {
              String password = VAULT.get(ref);
              if (password == null) {
                exchange.sendResponseHeaders(404, -1);
              } else {
                E2eOidc.json(
                    exchange,
                    200,
                    "{\"data\":{\"data\":{\"password\":\"" + password + "\"},\"metadata\":{}}}");
              }
            } else {
              String json =
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              String marker = "\"password\":\"";
              int start = json.indexOf(marker) + marker.length();
              VAULT.put(ref, json.substring(start, json.indexOf('"', start)));
              E2eOidc.json(exchange, 200, "{\"data\":{\"version\":1}}");
            }
          });
      vault.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", CORE::url);
    registry.add("spring.datasource.username", CORE::user);
    registry.add("spring.datasource.password", CORE::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("liberty.vault.uri", () -> "http://127.0.0.1:" + vault.getAddress().getPort());
    registry.add("liberty.security.oidc.issuer", () -> oidc.issuer);
    registry.add("liberty.security.allowed-origins", () -> "http://127.0.0.1:5173");
  }

  @Autowired TenantRepository tenants;
  @Autowired UserRoleRepository roles;

  private static void tenantSql(String... statements) throws Exception {
    try (Connection c =
            DriverManager.getConnection(TENANT.url(), TENANT.user(), TENANT.password());
        Statement st = c.createStatement()) {
      for (String sql : statements) {
        st.execute(sql);
      }
    }
  }

  @Test
  void stack() throws Exception {
    tenantSql(
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, cliente NVARCHAR(100) NOT NULL,"
            + " total DECIMAL(12,2) NOT NULL)",
        "CREATE TABLE dbo.tb_pedido_item (id BIGINT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL"
            + " REFERENCES dbo.tb_pedido(id), sku VARCHAR(30) NOT NULL, quantidade INT NOT NULL)");
    if (Boolean.getBoolean("liberty.e2e.sample-data")) {
      tenantSql(
          "INSERT INTO dbo.tb_pedido (cliente, total) VALUES (N'Ana', 120.50), (N'Bruno', 80.00), (N'Carla', 15.90)",
          "INSERT INTO dbo.tb_pedido_item (pedido_id, sku, quantidade) VALUES (1, 'A-1', 2), (1, 'B-7', 1), (2, 'A-1', 5), (3, 'C-3', 1)");
    }
    long acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    roles.save(new UserRoleEntity("integrador", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("integrador", acme, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("aprovador", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("aprovador", acme, TenantRole.PUBLISHER));
    roles.save(
        new UserRoleEntity(
            "admin", acme, TenantRole.VIEWER)); // seletor de tenant da tela de administração

    HttpClient http = HttpClient.newHttpClient();
    String admin = oidc.accessToken("admin", List.of("liberty-platform-admin"));
    for (String env : List.of("HML", "PRD")) {
      HttpResponse<String> saved =
          http.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:18080/api/v1/platform/tenants/acme/connections/" + env))
                  .header("Authorization", "Bearer " + admin)
                  .header("Content-Type", "application/json")
                  .PUT(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"jdbcUrl\":\""
                              + TENANT.url()
                              + "\",\"dbUsername\":\""
                              + TENANT.user()
                              + "\",\"password\":\""
                              + TENANT.password()
                              + "\",\"poolMax\":3}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (saved.statusCode() != 200) {
        throw new IllegalStateException(
            "conexão " + env + " -> " + saved.statusCode() + " " + saved.body());
      }
    }
    java.nio.file.Files.writeString(
        Path.of("target", "e2e-info.properties"),
        "coreUrl="
            + CORE.url()
            + "\nvaultUri=http://127.0.0.1:"
            + vault.getAddress().getPort()
            + "\n");
    ready = true;

    Path stop = Path.of("target", "e2e.stop");
    Files.deleteIfExists(stop);
    long deadline = System.currentTimeMillis() + Long.getLong("liberty.e2e.minutes", 30L) * 60_000L;
    while (!Files.exists(stop) && System.currentTimeMillis() < deadline) {
      Thread.sleep(500);
    }
    oidc.close();
    vault.stop(0);
  }
}
