package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.engine.exports.ExportLinks;
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
import java.sql.Statement;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Exportações de ponta a ponta: pedido, geração pela fila, URL assinada e download; e o critério da
 * E24, "download permitido só ao tenant dono".
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ExportContractTest {

  private static final CoreTestDatabase.Scratch TENANT_DB = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch CORE_DB = CoreTestDatabase.create();
  private static final java.nio.file.Path STORE;
  private static HttpServer vault;
  private static OidcStub oidc;

  static {
    try {
      STORE = java.nio.file.Files.createTempDirectory("liberty-export-test");
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
    registry.add("spring.datasource.url", CORE_DB::url);
    registry.add("spring.datasource.username", CORE_DB::user);
    registry.add("spring.datasource.password", CORE_DB::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("liberty.vault.uri", () -> "http://127.0.0.1:" + vault.getAddress().getPort());
    registry.add("liberty.vault.token", () -> "teste");
    registry.add("liberty.security.oidc.issuer", () -> oidc.issuer);
    registry.add("liberty.queue.enabled", () -> "true");
    registry.add("liberty.queue.poll-interval", () -> "100ms");
    registry.add("liberty.exports.directory", STORE::toString);
    registry.add(
        "liberty.exports.signing-key", () -> "chave-de-teste-com-mais-de-trinta-e-dois-caracteres");
    registry.add("liberty.exports.max-rows", () -> "5");
  }

  @AfterAll
  static void stop() {
    oidc.close();
    vault.stop(0);
  }

  @Autowired MockMvc mvc;
  @Autowired TenantRepository tenants;
  @Autowired TenantConnectionRepository connections;
  @Autowired UserRoleRepository roles;
  @Autowired ExportLinks links;
  @Autowired br.com.liberty.engine.exports.ExportService exports;

  private static boolean seeded;

  @BeforeEach
  void seed() throws Exception {
    if (seeded) {
      return;
    }
    seeded = true;
    try (Connection c =
            DriverManager.getConnection(TENANT_DB.url(), TENANT_DB.user(), TENANT_DB.password());
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE dbo.tb_cliente (codigo VARCHAR(20) PRIMARY KEY, nome NVARCHAR(50) NOT NULL, saldo DECIMAL(10,2) NULL)");
      st.execute(
          "INSERT INTO dbo.tb_cliente VALUES ('C1', N'Ana, \"a\"', -5.50), ('C2', N'=2+2', 10), ('C3', N'Cá', NULL)");
      st.execute("CREATE TABLE dbo.tb_grande (id INT PRIMARY KEY)");
      st.execute(
          "INSERT INTO dbo.tb_grande SELECT TOP 20 ROW_NUMBER() OVER (ORDER BY object_id) FROM sys.all_objects");
    }
    long acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    long beta = tenants.save(new TenantEntity("beta", "Beta", "STANDARD")).getId();
    for (long tenant : new long[] {acme, beta}) {
      String slug = tenant == acme ? "acme" : "beta";
      for (EnvironmentKind env : EnvironmentKind.values()) {
        connections.save(
            new TenantConnectionEntity(
                tenant,
                env,
                TENANT_DB.url(),
                TENANT_DB.user(),
                "liberty/tenants/" + slug + "/" + env.name().toLowerCase(),
                3));
      }
    }
    roles.save(new UserRoleEntity("dan", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("ana", acme, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("bia", acme, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("bruno", beta, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("bruno", beta, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("bela", beta, TenantRole.PUBLISHER));
    publishList(
        "acme", "ana", "bia", "clientes", "dbo.tb_cliente", "[\"codigo\",\"nome\",\"saldo\"]");
    publishList("acme", "ana", "bia", "grande", "dbo.tb_grande", "[\"id\"]");
    publishList(
        "beta", "bruno", "bela", "clientes-beta", "dbo.tb_cliente", "[\"codigo\",\"nome\"]");
  }

  private void publishList(
      String slug, String author, String approver, String key, String table, String columns)
      throws Exception {
    String base = "/api/v1/tenants/" + slug + "/integrations/" + key;
    call(
        post("/api/v1/tenants/" + slug + "/integrations"),
        author,
        "{\"key\":\"" + key + "\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/" + key + "\"}");
    call(
        put(base + "/draft"),
        author,
        "{\"schemaVersion\":1,\"integration\":{\"key\":\""
            + key
            + "\",\"name\":\"x\",\"method\":\"GET\",\"route\":\"/"
            + key
            + "\"},\"request\":{\"params\":[]},\"operation\":{\"type\":\"SELECT\",\"select\":{\"table\":\""
            + table
            + "\",\"columns\":"
            + columns
            + ",\"pageSize\":{\"default\":2,\"max\":2}}}}");
    call(post(base + "/versions/1/submit"), author, null);
    var published = call(post(base + "/versions/1/publish/HML"), approver, "{\"comment\":\"ok\"}");
    assertThat(published.getStatus()).as(published.getContentAsString()).isEqualTo(200);
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return JSONObjectUtils.parse(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private long request(String slug, String user, String integration) throws Exception {
    var response =
        call(
            post("/api/v1/tenants/" + slug + "/exports"),
            user,
            "{\"integration\":\"" + integration + "\",\"environment\":\"HML\"}");
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
    return ((Number) json(response).get("id")).longValue();
  }

  private Map<String, Object> await(String slug, String user, long id, String status)
      throws Exception {
    long limit = System.currentTimeMillis() + 30_000;
    Map<String, Object> view = Map.of();
    while (System.currentTimeMillis() < limit) {
      view = json(call(get("/api/v1/tenants/" + slug + "/exports/" + id), user, null));
      if (status.equals(view.get("status"))) {
        return view;
      }
      Thread.sleep(100);
    }
    assertThat(view).containsEntry("status", status);
    return view;
  }

  private String linkOf(String slug, String user, long id) throws Exception {
    var response = call(post("/api/v1/tenants/" + slug + "/exports/" + id + "/link"), user, null);
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return (String) json(response).get("url");
  }

  // ---------- fluxo ----------

  @Test
  void anExportIsGeneratedByTheQueueAndDownloadedWithTheSignedLinkAlone() throws Exception {
    long id = request("acme", "ana", "clientes");

    var done = await("acme", "ana", id, "COMPLETED");
    String url = linkOf("acme", "ana", id);
    var file = mvc.perform(get(url)).andReturn().getResponse(); // sem Authorization

    assertThat(done).containsEntry("integration", "clientes").containsEntry("format", "CSV");
    assertThat(file.getStatus()).isEqualTo(200);
    assertThat(file.getContentType()).startsWith("text/csv");
    assertThat(file.getHeader("Content-Disposition"))
        .contains("attachment")
        .contains("exportacao-" + id);
    assertThat(file.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(file.getHeader("Cache-Control")).contains("no-store");
    String csv = file.getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("﻿codigo,nome,saldo\r\n");
    assertThat(csv).contains("C1,\"Ana, \"\"a\"\"\",-5.50\r\n");
    assertThat(csv).as("injeção de fórmula neutralizada").contains("C2,'=2+2,10.00\r\n");
    assertThat(csv).contains("C3,Cá,\r\n");
  }

  @Test
  void pagesAreWalkedUntilTheLimitAndTooBigExportsFailCleanly() throws Exception {
    long id = request("acme", "ana", "grande"); // 20 linhas, limite do teste: 5

    var failed = await("acme", "ana", id, "FAILED");

    assertThat((String) failed.get("error")).contains("limite de 5 linhas");
    assertThat(call(post("/api/v1/tenants/acme/exports/" + id + "/link"), "ana", null).getStatus())
        .as("falha não tem arquivo")
        .isEqualTo(409);
    try (var files = java.nio.file.Files.walk(STORE)) {
      assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count())
          .as("nada parcial fica no armazenamento")
          .isZero();
    }
  }

  // ---------- critério da E24: só o tenant dono baixa ----------

  @Test
  void onlyTheOwningTenantCanDownload() throws Exception {
    long id = request("acme", "ana", "clientes");
    await("acme", "ana", id, "COMPLETED");
    String url = linkOf("acme", "ana", id);
    String token = url.substring(url.indexOf("token=") + 6);

    assertThat(call(get("/api/v1/tenants/acme/exports/" + id), "bruno", null).getStatus())
        .as("usuário de outro tenant não vê o pedido")
        .isEqualTo(403);
    assertThat(
            call(post("/api/v1/tenants/acme/exports/" + id + "/link"), "bruno", null).getStatus())
        .isEqualTo(403);
    assertThat(call(get("/api/v1/tenants/beta/exports/" + id), "bruno", null).getStatus())
        .as("pelo próprio tenant, o id do vizinho não existe")
        .isEqualTo(404);
    assertThat(
            call(post("/api/v1/tenants/beta/exports/" + id + "/link"), "bruno", null).getStatus())
        .isEqualTo(404);
    assertThat(call(get("/api/v1/tenants/acme/exports/" + id), "dan", null).getStatus())
        .as("VIEWER não exporta nem lê exportações")
        .isEqualTo(403);

    String forgedTenant = links.sign("beta", id, Instant.now().plusSeconds(300));
    String expired = links.sign("acme", id, Instant.now().minusSeconds(5));
    String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
    for (String bad : new String[] {tampered, expired, "lixo", "a.b", token + "x"}) {
      assertThat(
              mvc.perform(get("/api/v1/exports/download").param("token", bad))
                  .andReturn()
                  .getResponse()
                  .getStatus())
          .as(bad)
          .isEqualTo(403);
    }
    assertThat(
            mvc.perform(get("/api/v1/exports/download").param("token", forgedTenant))
                .andReturn()
                .getResponse()
                .getStatus())
        .as("assinatura válida de outro tenant não alcança o pedido do dono")
        .isEqualTo(404);
    assertThat(mvc.perform(get("/api/v1/exports/download")).andReturn().getResponse().getStatus())
        .isEqualTo(400);
    assertThat(
            mvc.perform(get("/api/v1/exports/download").param("token", token))
                .andReturn()
                .getResponse()
                .getStatus())
        .as("o link legítimo continua valendo")
        .isEqualTo(200);
  }

  // ---------- pedidos inválidos e vencimento ----------

  @Test
  void invalidRequestsAreRefusedBeforeAnyWork() throws Exception {
    String base = "/api/v1/tenants/acme/exports";
    assertThat(
            call(
                    post(base),
                    "ana",
                    "{\"integration\":\"clientes\",\"environment\":\"HML\",\"format\":\"XLSX\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            call(post(base), "ana", "{\"integration\":\"clientes\",\"environment\":\"XXX\"}")
                .getStatus())
        .isEqualTo(400);
    assertThat(
            call(
                    post(base),
                    "ana",
                    "{\"integration\":\"clientes\",\"environment\":\"HML\",\"params\":{\"x\":\"1\"}}")
                .getStatus())
        .as("parâmetro não declarado")
        .isEqualTo(400);
    assertThat(
            call(post(base), "ana", "{\"integration\":\"nao-existe\",\"environment\":\"HML\"}")
                .getStatus())
        .isEqualTo(404);
    assertThat(
            call(post(base), "ana", "{\"integration\":\"clientes\",\"environment\":\"PRD\"}")
                .getStatus())
        .as("não publicada em PRD")
        .isEqualTo(404);
    assertThat(
            call(post(base), "ana", "{\"integration\":\"clientes-beta\",\"environment\":\"HML\"}")
                .getStatus())
        .as("integração de outro tenant")
        .isEqualTo(404);
  }

  @Test
  void expiredFilesAreRefusedAndPurged() throws Exception {
    long id = request("acme", "ana", "clientes");
    await("acme", "ana", id, "COMPLETED");
    try (Connection c =
            DriverManager.getConnection(CORE_DB.url(), CORE_DB.user(), CORE_DB.password());
        Statement st = c.createStatement()) {
      st.execute(
          "UPDATE int_export_job SET expires_at = DATEADD(minute, -1, SYSUTCDATETIME()) WHERE id = "
              + id);
    }

    assertThat(call(post("/api/v1/tenants/acme/exports/" + id + "/link"), "ana", null).getStatus())
        .isEqualTo(410);
    exports.purgeExpired();
    try (var files = java.nio.file.Files.walk(STORE)) {
      assertThat(files.filter(p -> p.getFileName().toString().equals(id + ".csv")).count())
          .isZero();
    }
  }
}
