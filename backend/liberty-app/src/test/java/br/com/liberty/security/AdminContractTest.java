package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Administração do tenant e consulta de auditoria, de ponta a ponta, com um cofre falso que guarda
 * o que recebe.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AdminContractTest {

  private static final CoreTestDatabase.Scratch CORE_DB = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch TENANT_DB = CoreTestDatabase.create();
  private static final Map<String, String> VAULT = new ConcurrentHashMap<>();
  private static HttpServer vault;
  private static OidcStub oidc;

  static {
    try {
      oidc = new OidcStub();
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/",
          exchange -> {
            String ref = exchange.getRequestURI().getPath().substring("/v1/secret/data/".length());
            if (exchange.getRequestMethod().equals("GET")) {
              String password = VAULT.get(ref);
              byte[] body =
                  ("{\"data\":{\"data\":{\"password\":\"" + password + "\"},\"metadata\":{}}}")
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(
                  password == null ? 404 : 200, password == null ? -1 : body.length);
              if (password != null) {
                exchange.getResponseBody().write(body);
              }
            } else {
              String json =
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              Map<?, ?> data;
              try {
                data = (Map<?, ?>) JSONObjectUtils.parse(json).get("data");
              } catch (java.text.ParseException e) {
                throw new IOException(e);
              }
              VAULT.put(ref, String.valueOf(data.get("password")));
              byte[] body = "{\"data\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            }
            exchange.close();
          });
      vault.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", CORE_DB::url);
    registry.add("spring.datasource.username", CORE_DB::user);
    registry.add("spring.datasource.password", CORE_DB::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("liberty.vault.uri", () -> "http://127.0.0.1:" + vault.getAddress().getPort());
    registry.add("liberty.vault.token", () -> "teste");
    registry.add("liberty.security.oidc.issuer", () -> oidc.issuer);
  }

  @AfterAll
  static void stop() {
    oidc.close();
    vault.stop(0);
  }

  @Autowired MockMvc mvc;
  @Autowired TenantRepository tenants;
  @Autowired UserRoleRepository roles;

  private static boolean seeded;
  private static long acme;
  private static long beta;

  @BeforeEach
  void seed() {
    if (seeded) {
      return;
    }
    seeded = true;
    acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    beta = tenants.save(new TenantEntity("beta", "Beta", "STANDARD")).getId();
    roles.save(new UserRoleEntity("viewer", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("integrator", acme, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("publisher", acme, TenantRole.PUBLISHER));
  }

  // ---------- helpers ----------

  private String adminToken() {
    return oidc.userToken(
        "admin", Map.of("realm_access", Map.of("roles", List.of("liberty-platform-admin"))));
  }

  private MockHttpServletResponse call(
      MockHttpServletRequestBuilder request, String token, String body) throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse admin(MockHttpServletRequestBuilder request, String body)
      throws Exception {
    return call(request, adminToken(), body);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return JSONObjectUtils.parse(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static void sql(CoreTestDatabase.Scratch db, String statement) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      st.execute(statement);
    }
  }

  private static String connectionBody(String password) {
    return "{\"jdbcUrl\":\""
        + TENANT_DB.url()
        + "\",\"dbUsername\":\""
        + TENANT_DB.user()
        + "\",\"poolMax\":3"
        + (password == null ? "" : ",\"password\":\"" + password + "\"")
        + "}";
  }

  // ---------- acesso ----------

  @Test
  void onlyThePlatformAdministratorReachesTenantAdministration() throws Exception {
    for (String user : List.of("viewer", "integrator", "publisher")) {
      assertThat(
              call(get("/api/v1/platform/tenants/acme/connections"), oidc.userToken(user), null)
                  .getStatus())
          .as(user)
          .isEqualTo(403);
      assertThat(
              call(get("/api/v1/platform/tenants/acme/clients"), oidc.userToken(user), null)
                  .getStatus())
          .isEqualTo(403);
      assertThat(
              call(
                      put("/api/v1/platform/tenants/acme/users/x/roles/VIEWER"),
                      oidc.userToken(user),
                      null)
                  .getStatus())
          .isEqualTo(403);
    }
    assertThat(admin(get("/api/v1/platform/tenants/acme/connections"), null).getStatus())
        .isEqualTo(200);
    assertThat(admin(get("/api/v1/platform/tenants/inexistente/clients"), null).getStatus())
        .isEqualTo(404);
  }

  // ---------- conexões ----------

  @Test
  void connectionsStorePasswordsOnlyInTheVaultAndCanBeTested() throws Exception {
    assertThat(
            admin(put("/api/v1/platform/tenants/beta/connections/HML"), connectionBody(null))
                .getStatus())
        .as("criação exige a senha")
        .isEqualTo(400);
    assertThat(
            admin(
                    put("/api/v1/platform/tenants/beta/connections/HML"),
                    "{\"jdbcUrl\":\"jdbc:sqlserver://h:1433;encrypt=false\",\"dbUsername\":\"u\",\"password\":\"p\"}")
                .getStatus())
        .as("URL sem criptografia")
        .isEqualTo(400);

    var saved =
        admin(
            put("/api/v1/platform/tenants/beta/connections/hml"),
            connectionBody(TENANT_DB.password()));
    var list = admin(get("/api/v1/platform/tenants/beta/connections"), null);

    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    assertThat(VAULT).containsEntry("liberty/tenants/beta/hml", TENANT_DB.password());
    assertThat(saved.getContentAsString())
        .doesNotContain(TENANT_DB.password())
        .doesNotContain("password");
    assertThat(list.getContentAsString())
        .contains("\"environment\":\"HML\"")
        .doesNotContain(TENANT_DB.password());
    assertThat(json(admin(post("/api/v1/platform/tenants/beta/connections/HML/test"), null)))
        .containsEntry("ok", true);

    // atualizar sem senha mantém a do cofre; senha errada no cofre faz o teste falhar sem vazar
    // detalhes
    assertThat(
            admin(put("/api/v1/platform/tenants/beta/connections/HML"), connectionBody(null))
                .getStatus())
        .isEqualTo(200);
    assertThat(VAULT).containsEntry("liberty/tenants/beta/hml", TENANT_DB.password());
    admin(put("/api/v1/platform/tenants/beta/connections/HML"), connectionBody("senha-errada"));
    var failed = json(admin(post("/api/v1/platform/tenants/beta/connections/HML/test"), null));
    assertThat(failed).containsEntry("ok", false);
    assertThat(String.valueOf(failed.get("message")))
        .doesNotContain(TENANT_DB.user())
        .doesNotContain("senha-errada");
    assertThat(admin(post("/api/v1/platform/tenants/beta/connections/PRD/test"), null).getStatus())
        .isEqualTo(404);
    assertThat(admin(post("/api/v1/platform/tenants/beta/connections/XYZ/test"), null).getStatus())
        .isEqualTo(400);
  }

  // ---------- clientes OAuth2 ----------

  private int tokenStatus(String clientId, String secret) throws Exception {
    return mvc.perform(
            post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(
                                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void clientsAreCreatedWithAOneTimeSecretAndRevokedOnDemand() throws Exception {
    String body =
        "{\"clientId\":\"sistema-erp\",\"environment\":\"HML\",\"scopes\":[\"integration:execute:pedidos\"]}";

    var created = admin(post("/api/v1/platform/tenants/acme/clients"), body);
    assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    String secret = (String) json(created).get("clientSecret");
    assertThat(secret).hasSizeGreaterThan(30);
    assertThat(created.getHeader("Cache-Control")).contains("no-store");

    var list = admin(get("/api/v1/platform/tenants/acme/clients"), null).getContentAsString();
    assertThat(list)
        .contains("sistema-erp")
        .contains("integration:execute:pedidos")
        .doesNotContain(secret)
        .doesNotContain("secretHash");
    assertThat(tokenStatus("sistema-erp", secret)).as("o segredo gerado autentica").isEqualTo(200);
    assertThat(tokenStatus("sistema-erp", "errado")).isEqualTo(401);

    assertThat(admin(post("/api/v1/platform/tenants/acme/clients"), body).getStatus())
        .isEqualTo(409);
    assertThat(
            admin(
                    post("/api/v1/platform/tenants/acme/clients"),
                    "{\"clientId\":\"X\",\"environment\":\"DEV\",\"scopes\":[\"admin\"]}")
                .getStatus())
        .isEqualTo(400);

    assertThat(admin(delete("/api/v1/platform/tenants/beta/clients/sistema-erp"), null).getStatus())
        .as("não revoga cliente de outro tenant")
        .isEqualTo(404);
    assertThat(admin(delete("/api/v1/platform/tenants/acme/clients/sistema-erp"), null).getStatus())
        .isEqualTo(204);
    Thread.sleep(400); // o cache de clientes do teste dura 200 ms
    assertThat(tokenStatus("sistema-erp", secret))
        .as("cliente revogado não autentica")
        .isEqualTo(401);
  }

  // ---------- usuários e papéis ----------

  @Test
  void theOutboundHostAllowListIsManagedByThePlatformAdministratorOnly() throws Exception {
    String path = "/api/v1/platform/tenants/acme/http-hosts/";

    assertThat(call(put(path + "api.exemplo.com"), oidc.userToken("publisher"), null).getStatus())
        .isEqualTo(403);
    assertThat(admin(put(path + "api.exemplo.com"), null).getStatus()).isEqualTo(204);
    assertThat(admin(put(path + "api.exemplo.com"), null).getStatus())
        .as("idempotente")
        .isEqualTo(204);
    assertThat(admin(put(path + "pagamentos.exemplo.com:8443"), null).getStatus()).isEqualTo(204);
    String listed =
        admin(get("/api/v1/platform/tenants/acme/http-hosts"), null).getContentAsString();
    assertThat(listed)
        .contains("api.exemplo.com", "pagamentos.exemplo.com:8443")
        .contains("\"createdBy\":\"admin\"");
    for (String bad :
        List.of(
            "127.0.0.1",
            "10.0.0.1:443",
            "localhost",
            "Api.Exemplo.com",
            "-x.com",
            "a..com",
            "exemplo.com.",
            "x.com:1")) {
      assertThat(admin(put(path + bad), null).getStatus()).as(bad).isEqualTo(400);
    }
    assertThat(admin(delete(path + "api.exemplo.com"), null).getStatus()).isEqualTo(204);
    assertThat(admin(get("/api/v1/platform/tenants/acme/http-hosts"), null).getContentAsString())
        .doesNotContain("\"api.exemplo.com\"");
    assertThat(
            admin(put("/api/v1/platform/tenants/inexistente/http-hosts/api.exemplo.com"), null)
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void rolesGrantedByTheAdministratorTakeEffectAtOnce() throws Exception {
    assertThat(
            call(get("/api/v1/tenants/acme/integrations"), oidc.userToken("novato"), null)
                .getStatus())
        .isEqualTo(403);

    assertThat(
            admin(put("/api/v1/platform/tenants/acme/users/novato/roles/VIEWER"), null).getStatus())
        .isEqualTo(204);
    assertThat(
            admin(put("/api/v1/platform/tenants/acme/users/novato/roles/VIEWER"), null).getStatus())
        .as("idempotente")
        .isEqualTo(204);
    assertThat(
            call(get("/api/v1/tenants/acme/integrations"), oidc.userToken("novato"), null)
                .getStatus())
        .isEqualTo(200);
    assertThat(admin(get("/api/v1/platform/tenants/acme/users"), null).getContentAsString())
        .contains("novato")
        .contains("VIEWER");

    assertThat(
            admin(delete("/api/v1/platform/tenants/acme/users/novato/roles/VIEWER"), null)
                .getStatus())
        .isEqualTo(204);
    assertThat(
            call(get("/api/v1/tenants/acme/integrations"), oidc.userToken("novato"), null)
                .getStatus())
        .isEqualTo(403);
    assertThat(
            admin(put("/api/v1/platform/tenants/acme/users/novato/roles/CHEFE"), null).getStatus())
        .isEqualTo(400);
    assertThat(
            admin(put("/api/v1/platform/tenants/acme/users/a%20b/roles/VIEWER"), null).getStatus())
        .isEqualTo(400);
  }

  // ---------- auditoria ----------

  private static void audit(long tenant, String key, int status, String requestId, LocalDateTime at)
      throws SQLException {
    sql(
        CORE_DB,
        "INSERT INTO int_audit_execution (created_at, tenant_id, integration_key, environment, http_method, route,"
            + " status_code, duration_ms, error_code, request_id, client_id) VALUES ('"
            + at.toString()
            + "', "
            + tenant
            + ", '"
            + key
            + "', 'HML', 'GET', '/x', "
            + status
            + ", 12, "
            + (status >= 400 ? "'validation'" : "NULL")
            + ", '"
            + requestId
            + "', 'cli')");
  }

  @Test
  void theAuditQueryFiltersPagesAndNeverCrossesTenants() throws Exception {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    for (int i = 0; i < 5; i++) {
      audit(acme, "pedidos", 200, "ok-" + i, now.minusMinutes(i + 1));
    }
    audit(acme, "pedidos", 503, "falha-1", now.minusMinutes(10));
    audit(acme, "clientes", 404, "falha-2", now.minusMinutes(11));
    audit(acme, "antigo", 200, "velho-1", now.minusDays(3));
    audit(beta, "pedidos", 200, "de-outro-tenant", now.minusMinutes(2));
    String base = "/api/v1/tenants/acme/audit/executions";
    String token = oidc.userToken("integrator");

    var all = call(get(base), token, null);
    assertThat(all.getStatus()).isEqualTo(200);
    assertThat(all.getContentAsString())
        .contains("falha-1")
        .contains("ok-0")
        .doesNotContain("velho-1")
        .doesNotContain("de-outro-tenant");
    assertThat(all.getHeader("Cache-Control")).contains("no-store");

    assertThat(call(get(base).param("status", "5xx"), token, null).getContentAsString())
        .contains("falha-1")
        .doesNotContain("falha-2");
    assertThat(call(get(base).param("status", "404"), token, null).getContentAsString())
        .contains("falha-2")
        .doesNotContain("falha-1");
    assertThat(call(get(base).param("integration", "clientes"), token, null).getContentAsString())
        .contains("falha-2")
        .doesNotContain("ok-0");
    assertThat(call(get(base).param("requestId", "ok-3"), token, null).getContentAsString())
        .contains("ok-3")
        .doesNotContain("ok-2");

    var firstPage = json(call(get(base).param("size", "3"), token, null));
    var secondPage = json(call(get(base).param("size", "3").param("page", "1"), token, null));
    assertThat(firstPage).containsEntry("hasMore", true);
    assertThat((List<?>) firstPage.get("items")).hasSize(3);
    assertThat((List<?>) secondPage.get("items")).hasSize(3);
    assertThat(json(call(get(base).param("size", "3").param("page", "2"), token, null)))
        .containsEntry("hasMore", false);

    var wide =
        call(
            get(base)
                .param("from", now.minusDays(5).toString() + "Z")
                .param("to", now.plusMinutes(1).toString() + "Z"),
            token,
            null);
    assertThat(wide.getContentAsString()).contains("velho-1");
  }

  @Test
  void auditQueryValidatesItsFiltersAndNeedsTheIntegratorRole() throws Exception {
    String base = "/api/v1/tenants/acme/audit/executions";
    String token = oidc.userToken("integrator");

    assertThat(call(get(base), oidc.userToken("viewer"), null).getStatus()).isEqualTo(403);
    assertThat(call(get(base).param("status", "9xx"), token, null).getStatus()).isEqualTo(400);
    assertThat(call(get(base).param("integration", "A B;"), token, null).getStatus())
        .isEqualTo(400);
    assertThat(call(get(base).param("requestId", "x' OR 1=1"), token, null).getStatus())
        .isEqualTo(400);
    assertThat(call(get(base).param("size", "1000"), token, null).getStatus()).isEqualTo(400);
    assertThat(call(get(base).param("from", "ontem"), token, null).getStatus()).isEqualTo(400);
    assertThat(
            call(
                    get(base)
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2026-01-01T00:00:00Z"),
                    token,
                    null)
                .getStatus())
        .as("janela maior que 31 dias")
        .isEqualTo(400);
    assertThat(call(get("/api/v1/tenants/beta/audit/executions"), token, null).getStatus())
        .as("o integrador de um tenant não lê a auditoria de outro")
        .isEqualTo(403);
  }
}
