package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ciclo de vida de ponta a ponta: usuários com papéis, quatro olhos, ambientes e rollback. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LifecycleContractTest {

  private static final CoreTestDatabase.Scratch TENANT_DB = CoreTestDatabase.create();
  private static HttpServer vault;
  private static OidcStub oidc;

  static {
    try {
      oidc = new OidcStub();
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/liberty/tenants/",
          exchange -> {
            byte[] body =
                ("{\"data\":{\"data\":{\"password\":\""
                        + TENANT_DB.password()
                        + "\"},\"metadata\":{}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      vault.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    var core = CoreTestDatabase.create();
    registry.add("spring.datasource.url", core::url);
    registry.add("spring.datasource.username", core::user);
    registry.add("spring.datasource.password", core::password);
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
  @Autowired TenantConnectionRepository connections;
  @Autowired ApiClientRepository clients;
  @Autowired UserRoleRepository roles;
  @Autowired br.com.liberty.infrastructure.persistence.TenantHttpHostRepository httpHosts;
  @Autowired PasswordEncoder encoder;

  private static boolean seeded;

  @BeforeEach
  void seed() throws SQLException {
    if (seeded) {
      return;
    }
    seeded = true;
    try (Connection c =
            DriverManager.getConnection(TENANT_DB.url(), TENANT_DB.user(), TENANT_DB.password());
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE dbo.tb_cliente (codigo VARCHAR(20) PRIMARY KEY, nome NVARCHAR(50) NOT NULL)");
      st.execute("INSERT INTO dbo.tb_cliente VALUES ('C1', N'Ana'), ('C2', N'Bia')");
    }
    long acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    httpHosts.save(
        new br.com.liberty.infrastructure.persistence.TenantHttpHostEntity(
            acme, "api.exemplo.com", "teste"));
    for (EnvironmentKind env : EnvironmentKind.values()) {
      connections.save(
          new TenantConnectionEntity(
              acme,
              env,
              TENANT_DB.url(),
              TENANT_DB.user(),
              "liberty/tenants/acme/" + env.name().toLowerCase(),
              3));
    }
    roles.save(new UserRoleEntity("dan", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("ana", acme, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("bia", acme, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("eva", acme, TenantRole.PUBLISHER));
    String scopes = "integration:execute:lista integration:execute:lista2 integration:execute:rb";
    clients.save(
        new ApiClientEntity(
            acme, "acme-hml", encoder.encode("segredo-acme-hml"), EnvironmentKind.HML, scopes));
  }

  // ---------- helpers ----------

  private MockHttpServletResponse call(
      MockHttpServletRequestBuilder request, String user, String body) throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + oidc.userToken(user));
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private String clientToken() throws Exception {
    var response =
        mvc.perform(
                post("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "client_credentials")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(
                                    "acme-hml:segredo-acme-hml".getBytes(StandardCharsets.UTF_8))))
            .andReturn()
            .getResponse();
    return (String) JSONObjectUtils.parse(response.getContentAsString()).get("access_token");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return JSONObjectUtils.parse(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static String snapshot(String key, String route, String table) {
    return "{\"schemaVersion\":1,\"integration\":{\"key\":\""
        + key
        + "\",\"name\":\"Clientes\",\"method\":\"GET\",\"route\":\""
        + route
        + "\"},\"request\":{\"params\":[]},\"operation\":{\"type\":\"SELECT\",\"select\":{\"table\":\""
        + table
        + "\",\"columns\":[\"codigo\",\"nome\"]}}}";
  }

  private static String base(String key) {
    return "/api/v1/tenants/acme/integrations/" + key;
  }

  /** Cria a integração e deixa a versão 1 em revisão (autor: ana). */
  private void submitted(String key, String route, String table) throws Exception {
    assertThat(
            call(
                    post("/api/v1/tenants/acme/integrations"),
                    "ana",
                    "{\"key\":\""
                        + key
                        + "\",\"name\":\"Clientes\",\"method\":\"GET\",\"route\":\""
                        + route
                        + "\"}")
                .getStatus())
        .isEqualTo(201);
    assertThat(call(put(base(key) + "/draft"), "ana", snapshot(key, route, table)).getStatus())
        .isEqualTo(200);
    assertThat(call(post(base(key) + "/versions/1/submit"), "ana", null).getStatus())
        .isEqualTo(200);
  }

  private int publish(String key, int no, String env, String user) throws Exception {
    return call(
            post(base(key) + "/versions/" + no + "/publish/" + env), user, "{\"comment\":\"ok\"}")
        .getStatus();
  }

  private int execute(String route) throws Exception {
    return mvc.perform(
            get("/acme/api" + route).header(HttpHeaders.AUTHORIZATION, "Bearer " + clientToken()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  // ---------- testes ----------

  @Test
  void rolesDecideWhoCanDoWhat() throws Exception {
    String body = "{\"key\":\"papeis\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/papeis\"}";

    assertThat(call(post("/api/v1/tenants/acme/integrations"), "dan", body).getStatus())
        .isEqualTo(403);
    assertThat(call(post("/api/v1/tenants/acme/integrations"), "ana", body).getStatus())
        .isEqualTo(201);
    assertThat(call(post("/api/v1/tenants/acme/integrations"), "ana", body).getStatus())
        .isEqualTo(409);
    assertThat(call(get("/api/v1/tenants/acme/integrations"), "dan", null).getStatus())
        .isEqualTo(200);
    assertThat(
            call(
                    put(base("papeis") + "/draft"),
                    "dan",
                    snapshot("papeis", "/papeis", "dbo.tb_cliente"))
                .getStatus())
        .isEqualTo(403);
    assertThat(publish("papeis", 1, "HML", "ana")).as("integrador não publica").isEqualTo(403);
    assertThat(
            call(
                    post("/api/v1/tenants/acme/integrations"),
                    "ana",
                    "{\"key\":\"Ruim Key\",\"method\":\"X\",\"route\":\"\"}")
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void invalidSnapshotsAndIdentityMismatchesAreRefusedWithFieldErrors() throws Exception {
    call(
        post("/api/v1/tenants/acme/integrations"),
        "ana",
        "{\"key\":\"invalida\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/invalida\"}");

    var broken = call(put(base("invalida") + "/draft"), "ana", "{\"schemaVersion\":1}");
    var wrong =
        call(
            put(base("invalida") + "/draft"),
            "ana",
            snapshot("outra", "/invalida", "dbo.tb_cliente"));

    assertThat(broken.getStatus()).isEqualTo(422);
    assertThat(broken.getContentAsString()).contains("errors");
    assertThat(wrong.getStatus()).isEqualTo(422);
    assertThat(wrong.getContentAsString()).contains("identity_mismatch");
  }

  @Test
  void theAuthorCannotApproveOrPublishTheirOwnVersion() throws Exception {
    call(
        post("/api/v1/tenants/acme/integrations"),
        "eva",
        "{\"key\":\"quatro-olhos\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/quatro-olhos\"}");
    call(
        put(base("quatro-olhos") + "/draft"),
        "eva",
        snapshot("quatro-olhos", "/quatro-olhos", "dbo.tb_cliente"));
    call(post(base("quatro-olhos") + "/versions/1/submit"), "eva", null);

    assertThat(publish("quatro-olhos", 1, "HML", "eva")).isEqualTo(403);
    assertThat(call(post(base("quatro-olhos") + "/versions/1/reject"), "eva", null).getStatus())
        .isEqualTo(403);
    assertThat(publish("quatro-olhos", 1, "HML", "bia")).isEqualTo(200);
    assertThat(
            call(post(base("quatro-olhos") + "/environments/HML/rollback"), "bia", null)
                .getStatus())
        .as("publicada uma vez, não há versão anterior")
        .isEqualTo(409);
  }

  @Test
  void publishingGoesLiveImmediatelyAndPrdRequiresHmlFirst() throws Exception {
    submitted("lista", "/clientes", "dbo.tb_cliente");

    assertThat(execute("/clientes")).as("ainda não publicada").isEqualTo(404);
    assertThat(publish("lista", 1, "PRD", "bia")).as("PRD antes de HML").isEqualTo(409);
    assertThat(publish("lista", 1, "HML", "bia")).isEqualTo(200);
    assertThat(execute("/clientes")).isEqualTo(200);
    assertThat(publish("lista", 1, "PRD", "eva")).isEqualTo(200);

    var versions = call(get(base("lista") + "/versions"), "dan", null);
    assertThat(versions.getContentAsString())
        .contains("\"state\":\"PUBLISHED\"")
        .contains("\"reviewer\":\"bia\"");
    assertThat(
            call(delete(base("lista") + "/environments/HML/publication"), "bia", null).getStatus())
        .isEqualTo(204);
    assertThat(execute("/clientes")).as("despublicada").isEqualTo(404);
  }

  @Test
  void rollbackRestoresThePreviousVersionAtOnce() throws Exception {
    submitted("rb", "/rb", "dbo.tb_cliente");
    publish("rb", 1, "HML", "bia");
    call(
        put(base("rb") + "/draft"),
        "ana",
        snapshot("rb", "/rb", "dbo.tb_cliente").replace("\"Clientes\"", "\"Clientes v2\""));
    call(post(base("rb") + "/versions/2/submit"), "ana", null);
    assertThat(publish("rb", 2, "HML", "bia")).isEqualTo(200);

    var back = call(post(base("rb") + "/environments/HML/rollback"), "bia", null);
    var backAgain = call(post(base("rb") + "/environments/HML/rollback"), "bia", null);

    assertThat(back.getStatus()).isEqualTo(200);
    assertThat(json(back)).containsEntry("versionNo", 1L);
    assertThat(execute("/rb")).isEqualTo(200);
    assertThat(json(backAgain)).as("desfazer o rollback volta à v2").containsEntry("versionNo", 2L);
  }

  @Test
  void rejectedVersionsCannotBePublishedAndMissingTablesBlockPublication() throws Exception {
    submitted("lista2", "/clientes2", "dbo.tb_cliente");
    submitted("sem-tabela", "/sem-tabela", "dbo.nao_existe");

    assertThat(
            call(post(base("lista2") + "/versions/1/reject"), "bia", "{\"comment\":\"não\"}")
                .getStatus())
        .isEqualTo(200);
    assertThat(publish("lista2", 1, "HML", "bia")).isEqualTo(409);
    var blocked = call(post(base("sem-tabela") + "/versions/1/publish/HML"), "bia", null);
    assertThat(blocked.getStatus()).isEqualTo(422);
    assertThat(blocked.getContentAsString()).contains("unknown_table");
  }

  @Test
  void aSelectCannotHaveABeforeExecuteScript() throws Exception {
    call(
        post("/api/v1/tenants/acme/integrations"),
        "ana",
        "{\"key\":\"com-script\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/com-script\"}");
    String withScript =
        snapshot("com-script", "/com-script", "dbo.tb_cliente")
            .replace(
                "\"operation\"",
                "\"scripts\":{\"beforeExecute\":\"function beforeExecute(i){return i.body;}\"},\"operation\"");

    var response = call(put(base("com-script") + "/draft"), "ana", withScript);

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentAsString()).contains("before_execute_on_select");
  }

  @Test
  void theGeneratedOpenApiDescribesThePublishedIntegrations() throws Exception {
    submitted("doc", "/doc", "dbo.tb_cliente");
    publish("doc", 1, "HML", "bia");

    var doc = call(get("/api/v1/tenants/acme/environments/hml/openapi.json"), "dan", null);

    assertThat(doc.getStatus()).isEqualTo(200);
    assertThat(json(doc)).containsEntry("openapi", "3.1.0");
    assertThat(
            call(get("/api/v1/tenants/acme/environments/xyz/openapi.json"), "dan", null)
                .getStatus())
        .isEqualTo(400);
    assertThat(doc.getContentAsString()).contains("/acme/api/doc").contains("oauth2");
  }

  @Test
  void sandboxRunsUnpublishedVersionsInHmlAndRollsEverythingBack() throws Exception {
    submitted("sbx-lista", "/sbx-lista", "dbo.tb_cliente");
    call(
        post("/api/v1/tenants/acme/integrations"),
        "ana",
        "{\"key\":\"sbx-cria\",\"name\":\"x\",\"method\":\"POST\",\"route\":\"/sbx-cria\"}");
    String insert =
        "{\"schemaVersion\":1,\"integration\":{\"key\":\"sbx-cria\",\"name\":\"x\",\"method\":\"POST\",\"route\":\"/sbx-cria\"},"
            + "\"request\":{\"params\":[],\"bodySchema\":{\"type\":\"object\"}},\"operation\":{\"type\":\"INSERT\",\"write\":{\"root\":{\"table\":\"dbo.tb_cliente\","
            + "\"fields\":[{\"column\":\"codigo\",\"source\":\"/codigo\"},{\"column\":\"nome\",\"source\":\"/nome\"}]}}}}";
    call(put(base("sbx-cria") + "/draft"), "ana", insert);

    var read = call(post(base("sbx-lista") + "/versions/1/sandbox"), "ana", "{}");
    var write =
        call(
            post(base("sbx-cria") + "/versions/1/sandbox"),
            "ana",
            "{\"body\":{\"codigo\":\"Z9\",\"nome\":\"Zé\"}}");
    var duplicate =
        call(
            post(base("sbx-cria") + "/versions/1/sandbox"),
            "ana",
            "{\"body\":{\"codigo\":\"C1\",\"nome\":\"Dup\"}}");
    var invalid = call(post(base("sbx-cria") + "/versions/1/sandbox"), "ana", "{\"body\":[1]}");

    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(json(read)).containsEntry("succeeded", true).containsEntry("rolledBack", true);
    assertThat(read.getContentAsString()).contains("SELECT");
    assertThat(write.getStatus()).as(write.getContentAsString()).isEqualTo(200);
    assertThat(json(write)).containsEntry("status", 201L).containsEntry("succeeded", true);
    assertThat(write.getContentAsString())
        .contains("INSERT INTO [dbo].[tb_cliente]")
        .doesNotContain("Z9");
    assertThat(json(duplicate)).containsEntry("succeeded", false).containsEntry("status", 409L);
    assertThat(json(invalid)).containsEntry("succeeded", false).containsEntry("status", 400L);
    try (Connection c =
            DriverManager.getConnection(TENANT_DB.url(), TENANT_DB.user(), TENANT_DB.password());
        Statement st = c.createStatement();
        var rs = st.executeQuery("SELECT COUNT(*) FROM dbo.tb_cliente WHERE codigo = 'Z9'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  private String httpWrite(String key, String script) {
    return "{\"schemaVersion\":1,\"integration\":{\"key\":\""
        + key
        + "\",\"name\":\"x\",\"method\":\"POST\",\"route\":\"/"
        + key
        + "\"},\"request\":{\"params\":[],\"bodySchema\":{\"type\":\"object\"}},\"scripts\":{\"beforeExecute\":\""
        + script
        + "\"},\"operation\":{\"type\":\"INSERT\",\"write\":{\"root\":{\"table\":\"dbo.tb_cliente\","
        + "\"fields\":[{\"column\":\"codigo\",\"source\":\"/codigo\"},{\"column\":\"nome\",\"source\":\"/nome\"}]}}}}";
  }

  @Test
  void sandboxSimulatesHttpFetchButStillEnforcesTheTenantAllowList() throws Exception {
    for (String key : java.util.List.of("sbx-http", "sbx-http-nega")) {
      call(
          post("/api/v1/tenants/acme/integrations"),
          "ana",
          "{\"key\":\""
              + key
              + "\",\"name\":\"x\",\"method\":\"POST\",\"route\":\"/"
              + key
              + "\"}");
    }
    call(
        put(base("sbx-http") + "/draft"),
        "ana",
        httpWrite(
            "sbx-http",
            "function beforeExecute(i){ const r = http.fetch('https://api.exemplo.com/x',"
                + " {method:'POST', body:{a:1}}); return { codigo: 'H' + r.status, nome: String(r.simulated) }; }"));
    call(
        put(base("sbx-http-nega") + "/draft"),
        "ana",
        httpWrite(
            "sbx-http-nega",
            "function beforeExecute(i){ http.fetch('https://nao-liberado.exemplo.com/'); return i; }"));

    var allowed = call(post(base("sbx-http") + "/versions/1/sandbox"), "ana", "{\"body\":{}}");
    var denied = call(post(base("sbx-http-nega") + "/versions/1/sandbox"), "ana", "{\"body\":{}}");

    assertThat(allowed.getStatus()).as(allowed.getContentAsString()).isEqualTo(200);
    assertThat(json(allowed)).containsEntry("succeeded", true).containsEntry("status", 201L);
    assertThat(json(denied)).containsEntry("succeeded", false).containsEntry("status", 500L);
    assertThat(denied.getContentAsString())
        .as("o motivo técnico não vaza")
        .doesNotContain("nao-liberado");
  }

  @Test
  void sandboxNeedsTheIntegratorRoleAndAnExistingVersion() throws Exception {
    assertThat(call(post(base("sbx-lista") + "/versions/1/sandbox"), "dan", "{}").getStatus())
        .isEqualTo(403);
    assertThat(call(post(base("nao-existe") + "/versions/1/sandbox"), "ana", "{}").getStatus())
        .isEqualTo(404);
    assertThat(call(post(base("papeis") + "/versions/9/sandbox"), "ana", "{}").getStatus())
        .isEqualTo(404);
  }

  @Test
  void theListShowsDraftsActiveVersionsPerEnvironmentAndHealth() throws Exception {
    submitted("saude", "/saude", "dbo.tb_cliente");
    assertThat(publish("saude", 1, "HML", "bia")).isEqualTo(200);
    call(
        post("/api/v1/tenants/acme/integrations"),
        "ana",
        "{\"key\":\"so-rascunho\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/so-rascunho\"}");
    call(
        put(base("so-rascunho") + "/draft"),
        "ana",
        snapshot("so-rascunho", "/so-rascunho", "dbo.tb_cliente"));

    var list = call(get("/api/v1/tenants/acme/integrations"), "dan", null);

    assertThat(list.getStatus()).isEqualTo(200);
    var items =
        (java.util.List<?>)
            com.nimbusds.jose.util.JSONObjectUtils.parse(
                    "{\"i\":" + list.getContentAsString() + "}")
                .get("i");
    @SuppressWarnings("unchecked")
    var saude =
        items.stream()
            .map(o -> (Map<String, Object>) o)
            .filter(m -> "saude".equals(m.get("key")))
            .findFirst()
            .orElseThrow();
    @SuppressWarnings("unchecked")
    var rascunho =
        items.stream()
            .map(o -> (Map<String, Object>) o)
            .filter(m -> "so-rascunho".equals(m.get("key")))
            .findFirst()
            .orElseThrow();
    assertThat(saude)
        .containsEntry("hmlVersionNo", 1L)
        .containsEntry("calls24h", 0L)
        .containsEntry("errors24h", 0L);
    assertThat(saude.get("prdVersionNo")).isNull();
    assertThat(saude.get("draftVersionNo")).isNull();
    assertThat(rascunho).containsEntry("draftVersionNo", 1L);
    assertThat(rascunho.get("hmlVersionNo")).isNull();
  }

  @Test
  void theCatalogOfTheTenantDatabaseFeedsTheMappingEditor() throws Exception {
    var tables = call(get("/api/v1/tenants/acme/environments/hml/catalog/tables"), "ana", null);
    var detail =
        call(
            get("/api/v1/tenants/acme/environments/HML/catalog/tables/dbo.tb_cliente"),
            "ana",
            null);

    assertThat(tables.getStatus()).isEqualTo(200);
    assertThat(tables.getContentAsString()).contains("tb_cliente").contains("\"kind\":\"TABLE\"");
    assertThat(detail.getStatus()).isEqualTo(200);
    assertThat(detail.getContentAsString())
        .contains("\"codigo\"")
        .contains("\"primaryKey\":true")
        .contains("\"required\":true");
    assertThat(
            call(
                    get("/api/v1/tenants/acme/environments/HML/catalog/tables/dbo.nao_existe"),
                    "ana",
                    null)
                .getStatus())
        .isEqualTo(404);
    assertThat(
            call(get("/api/v1/tenants/acme/environments/HML/catalog/tables/x;drop"), "ana", null)
                .getStatus())
        .isIn(400, 404);
    assertThat(
            call(get("/api/v1/tenants/acme/environments/HML/catalog/tables"), "dan", null)
                .getStatus())
        .as("VIEWER não vê o catálogo")
        .isEqualTo(403);
    assertThat(
            call(get("/api/v1/tenants/acme/environments/xyz/catalog/tables"), "ana", null)
                .getStatus())
        .isEqualTo(400);
  }
}
