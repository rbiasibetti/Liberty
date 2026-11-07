package br.com.liberty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.IntegrationEntity;
import br.com.liberty.infrastructure.persistence.IntegrationRepository;
import br.com.liberty.infrastructure.persistence.IntegrationVersionEntity;
import br.com.liberty.infrastructure.persistence.IntegrationVersionRepository;
import br.com.liberty.infrastructure.persistence.PublicationEntity;
import br.com.liberty.infrastructure.persistence.PublicationRepository;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriUtils;

/**
 * Contrato da API de leitura de ponta a ponta: token real, rotas publicadas no core, banco de
 * tenant real (SQL Server) e cofre falso. Cada teste é uma garantia visível ao consumidor da API.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ReadContractTest {

  private static final CoreTestDatabase.Scratch ACME_DB = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch BETA_DB = CoreTestDatabase.create();
  private static HttpServer vault;

  static {
    try {
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/liberty/tenants/",
          exchange -> {
            boolean ok = "teste".equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] body =
                ("{\"data\":{\"data\":{\"password\":\""
                        + ACME_DB.password()
                        + "\"},\"metadata\":{}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 403, ok ? body.length : -1);
            if (ok) {
              exchange.getResponseBody().write(body);
            }
            exchange.close();
          });
      vault.start();
    } catch (IOException e) {
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
    registry.add("liberty.security.oidc.issuer", () -> "http://127.0.0.1:1/realms/indisponivel");
  }

  @AfterAll
  static void stop() {
    vault.stop(0);
  }

  @Autowired MockMvc mvc;
  @Autowired TenantRepository tenants;
  @Autowired TenantConnectionRepository connections;
  @Autowired ApiClientRepository clients;
  @Autowired IntegrationRepository integrations;
  @Autowired IntegrationVersionRepository versions;
  @Autowired PublicationRepository publications;
  @Autowired PasswordEncoder encoder;
  @Autowired PublishedRoutes routes;

  private static boolean seeded;
  private static long acmeId;

  // ---------- dados ----------

  private static void sql(CoreTestDatabase.Scratch db, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  private static void createTenantData(CoreTestDatabase.Scratch db, int rows, String tag)
      throws SQLException {
    sql(
        db,
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, nr_pedido VARCHAR(30) NOT NULL, cliente_id INT NOT NULL,"
            + " vl_total DECIMAL(18,2) NOT NULL, dt_pedido DATETIME2(3) NOT NULL, status VARCHAR(20) NOT NULL, ativo BIT NOT NULL DEFAULT 1,"
            + " nota NVARCHAR(100) NULL)",
        "CREATE TABLE dbo.tb_resumo (id INT PRIMARY KEY, total DECIMAL(18,2) NOT NULL, origem VARCHAR(20) NOT NULL)",
        "INSERT INTO dbo.tb_resumo VALUES (1, 1234.50, '" + tag + "')",
        "CREATE TABLE dbo.tb_tipos (id INT PRIMARY KEY, texto NVARCHAR(50) NULL, grande BIGINT NULL, preco DECIMAL(18,4) NULL, ativo BIT NULL,"
            + " data DATE NULL, momento DATETIME2(3) NULL, chave UNIQUEIDENTIFIER NULL, anexo VARBINARY(8) NULL)",
        "INSERT INTO dbo.tb_tipos VALUES (1, N'olá', 9007199254740993, 12.3400, 1, '2026-11-06', '2026-11-06T10:15:30.123', '123E4567-E89B-12D3-A456-426614174000', 0x0102AB)",
        "INSERT INTO dbo.tb_tipos (id) VALUES (2)");
    for (int i = 1; i <= rows; i++) {
      sql(
          db,
          "INSERT INTO dbo.tb_pedido (nr_pedido, cliente_id, vl_total, dt_pedido, status, nota) VALUES ('"
              + tag
              + "-"
              + String.format("%04d", i)
              + "', "
              + (i % 3 + 1)
              + ", "
              + i * 10
              + ".50, '2026-01-"
              + String.format("%02d", i)
              + "T10:00:00', '"
              + (i % 2 == 0 ? "PAGO" : "ABERTO")
              + "', "
              + (i == 1 ? "N'primeiro'" : "NULL")
              + ")");
    }
  }

  private static final String PARAMS =
      "[{\"name\":\"cliente\",\"in\":\"query\",\"type\":\"integer\"},{\"name\":\"status\",\"in\":\"query\",\"type\":\"string\"},"
          + "{\"name\":\"prefixo\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"ids\",\"in\":\"query\",\"type\":\"string\"},"
          + "{\"name\":\"periodo\",\"in\":\"query\",\"type\":\"string\"},{\"name\":\"minimo\",\"in\":\"query\",\"type\":\"number\"}]";

  private static final String LIST_SELECT =
      "{\"table\":\"dbo.tb_pedido\",\"columns\":[\"id\",\"nr_pedido\",\"vl_total\",\"dt_pedido\",\"ativo\",\"nota\"],"
          + "\"filters\":[{\"param\":\"cliente\",\"column\":\"cliente_id\",\"operator\":\"=\"},{\"param\":\"status\",\"column\":\"status\",\"operator\":\"=\"},"
          + "{\"param\":\"prefixo\",\"column\":\"nr_pedido\",\"operator\":\"LIKE_PREFIX\"},{\"param\":\"ids\",\"column\":\"id\",\"operator\":\"IN\"},"
          + "{\"param\":\"periodo\",\"column\":\"dt_pedido\",\"operator\":\"BETWEEN\"},{\"param\":\"minimo\",\"column\":\"vl_total\",\"operator\":\">=\"}],"
          + "\"sortable\":[\"id\",\"vl_total\"],\"defaultSort\":{\"column\":\"id\",\"direction\":\"DESC\"},\"pageSize\":{\"default\":5,\"max\":20}}";

  private static String snapshot(
      String key, String method, String route, String params, String operation) {
    return "{\"schemaVersion\":1,\"integration\":{\"key\":\""
        + key
        + "\",\"name\":\""
        + key
        + "\",\"method\":\""
        + method
        + "\",\"route\":\""
        + route
        + "\"},"
        + "\"request\":{\"params\":"
        + params
        + (operation.contains("\"write\"") ? ",\"bodySchema\":{\"type\":\"object\"}" : "")
        + "},\"operation\":"
        + operation
        + "}";
  }

  private static String select(String select) {
    return "{\"type\":\"SELECT\",\"select\":" + select + "}";
  }

  private PublicationEntity publish(
      long tenantId, String key, String method, String route, EnvironmentKind env, String json) {
    IntegrationEntity integration =
        integrations
            .findByTenantIdAndHttpMethodAndRoutePattern(tenantId, method, route)
            .orElseGet(
                () -> integrations.save(new IntegrationEntity(tenantId, key, key, method, route)));
    IntegrationVersionEntity version =
        versions.save(
            new IntegrationVersionEntity(
                tenantId,
                integration.getId(),
                versions.lockAndGetMaxVersionNo(integration.getId()) + 1,
                VersionState.PUBLISHED,
                json,
                new byte[32],
                "ana"));
    return publications.save(
        new PublicationEntity(tenantId, integration.getId(), version.getId(), env, true, "bia"));
  }

  private ApiClientEntity client(long tenantId, String id, EnvironmentKind env, String scopes) {
    return clients.save(
        new ApiClientEntity(tenantId, id, encoder.encode("segredo-" + id), env, scopes));
  }

  @BeforeEach
  void seed() throws Exception {
    if (seeded) {
      return;
    }
    seeded = true;
    createTenantData(ACME_DB, 25, "P");
    createTenantData(BETA_DB, 2, "B");
    acmeId = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    long beta = tenants.save(new TenantEntity("beta", "Beta", "STANDARD")).getId();
    long down = tenants.save(new TenantEntity("fora", "Fora", "STANDARD")).getId();
    connections.save(
        new TenantConnectionEntity(
            acmeId,
            EnvironmentKind.HML,
            ACME_DB.url(),
            ACME_DB.user(),
            "liberty/tenants/acme/hml",
            3));
    connections.save(
        new TenantConnectionEntity(
            acmeId,
            EnvironmentKind.PRD,
            ACME_DB.url(),
            ACME_DB.user(),
            "liberty/tenants/acme/prd",
            3));
    connections.save(
        new TenantConnectionEntity(
            beta,
            EnvironmentKind.HML,
            BETA_DB.url(),
            BETA_DB.user(),
            "liberty/tenants/beta/hml",
            3));
    connections.save(
        new TenantConnectionEntity(
            down,
            EnvironmentKind.HML,
            "jdbc:sqlserver://127.0.0.1:1;databaseName=x;encrypt=true;trustServerCertificate=true",
            "u",
            "liberty/tenants/fora/hml",
            1));

    String all =
        "integration:execute:pedidos integration:execute:pedidos-criar integration:execute:pedido-por-numero integration:execute:resumo"
            + " integration:execute:tipos integration:execute:somente-hml integration:execute:somente-prd integration:execute:novo";
    client(acmeId, "acme-hml", EnvironmentKind.HML, all);
    client(acmeId, "acme-prd", EnvironmentKind.PRD, all);
    client(acmeId, "acme-limitado", EnvironmentKind.HML, "integration:execute:resumo");
    client(beta, "beta-hml", EnvironmentKind.HML, "integration:execute:pedidos");
    client(down, "fora-hml", EnvironmentKind.HML, "integration:execute:pedidos");

    String list = snapshot("pedidos", "GET", "/pedidos", PARAMS, select(LIST_SELECT));
    for (EnvironmentKind env : EnvironmentKind.values()) {
      publish(acmeId, "pedidos", "GET", "/pedidos", env, list);
    }
    publish(
        acmeId,
        "pedidos-criar",
        "POST",
        "/pedidos",
        EnvironmentKind.HML,
        snapshot(
            "pedidos-criar",
            "POST",
            "/pedidos",
            "[]",
            "{\"type\":\"INSERT\",\"write\":{\"root\":{\"table\":\"dbo.tb_pedido\",\"fields\":[{\"column\":\"nr_pedido\",\"constant\":\"X\"}]}}}"));
    String single =
        "{\"table\":\"dbo.tb_pedido\",\"columns\":[\"id\",\"nr_pedido\",\"vl_total\",\"status\"],\"filters\":[{\"param\":\"numero\",\"column\":\"nr_pedido\",\"operator\":\"=\",\"required\":true}],\"single\":true}";
    publish(
        acmeId,
        "pedido-por-numero",
        "GET",
        "/pedidos/{numero}",
        EnvironmentKind.HML,
        snapshot(
            "pedido-por-numero",
            "GET",
            "/pedidos/{numero}",
            "[{\"name\":\"numero\",\"in\":\"path\",\"type\":\"string\"}]",
            select(single)));
    publish(
        acmeId,
        "resumo",
        "GET",
        "/pedidos/resumo",
        EnvironmentKind.HML,
        snapshot(
            "resumo",
            "GET",
            "/pedidos/resumo",
            "[]",
            select(
                "{\"table\":\"dbo.tb_resumo\",\"columns\":[\"id\",\"total\",\"origem\"],\"single\":true}")));
    publish(
        acmeId,
        "tipos",
        "GET",
        "/tipos/{id}",
        EnvironmentKind.HML,
        snapshot(
            "tipos",
            "GET",
            "/tipos/{id}",
            "[{\"name\":\"id\",\"in\":\"path\",\"type\":\"integer\"}]",
            select(
                "{\"table\":\"dbo.tb_tipos\",\"columns\":[\"id\",\"texto\",\"grande\",\"preco\",\"ativo\",\"data\",\"momento\",\"chave\",\"anexo\"],"
                    + "\"filters\":[{\"param\":\"id\",\"column\":\"id\",\"operator\":\"=\",\"required\":true}],\"single\":true}")));
    String tiny = "{\"table\":\"dbo.tb_resumo\",\"columns\":[\"id\"],\"single\":true}";
    publish(
        acmeId,
        "somente-hml",
        "GET",
        "/somente-hml",
        EnvironmentKind.HML,
        snapshot("somente-hml", "GET", "/somente-hml", "[]", select(tiny)));
    publish(
        acmeId,
        "somente-prd",
        "GET",
        "/somente-prd",
        EnvironmentKind.PRD,
        snapshot("somente-prd", "GET", "/somente-prd", "[]", select(tiny)));
    publish(beta, "pedidos", "GET", "/pedidos", EnvironmentKind.HML, list);
    publish(down, "pedidos", "GET", "/pedidos", EnvironmentKind.HML, list);
  }

  // ---------- helpers ----------

  private String token(String clientId) throws Exception {
    var request =
        post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "client_credentials")
            .header(
                HttpHeaders.AUTHORIZATION,
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            (clientId + ":segredo-" + clientId).getBytes(StandardCharsets.UTF_8)));
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return (String) JSONObjectUtils.parse(response.getContentAsString()).get("access_token");
  }

  private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String token)
      throws Exception {
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse get(String path, String token, String... queryPairs)
      throws Exception {
    var request = MockMvcRequestBuilders.get(path);
    for (int i = 0; i < queryPairs.length; i += 2) {
      request.param(queryPairs[i], queryPairs[i + 1]);
    }
    return call(request, token);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return JSONObjectUtils.parse(response.getContentAsString(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(MockHttpServletResponse response)
      throws Exception {
    return (List<Map<String, Object>>) json(response).get("items");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> page(MockHttpServletResponse response) throws Exception {
    return (Map<String, Object>) json(response).get("page");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> errors(MockHttpServletResponse response)
      throws Exception {
    return (List<Map<String, Object>>) json(response).get("errors");
  }

  private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
    long limit = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < limit) {
      if (condition.get()) {
        return;
      }
      Thread.sleep(150);
    }
    assertThat(condition.get()).as("condição não foi atingida a tempo").isTrue();
  }

  private int status(String path, String token) throws Exception {
    return get(path, token).getStatus();
  }

  private static long count(CoreTestDatabase.Scratch db, String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ---------- contrato da listagem ----------

  @Test
  void listReturnsAPageWithTheDocumentedShapeAndHeaders() throws Exception {
    MockHttpServletResponse response = get("/acme/api/pedidos", token("acme-hml"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    assertThat(json(response).keySet()).containsExactly("items", "page");
    assertThat(page(response))
        .containsEntry("limit", 5L)
        .containsEntry("offset", 0L)
        .containsEntry("returned", 5L)
        .containsEntry("hasMore", true);
    List<Map<String, Object>> items = items(response);
    assertThat(items).extracting(i -> i.get("id")).containsExactly(25L, 24L, 23L, 22L, 21L);
    Map<String, Object> first = items.getFirst();
    assertThat(first.keySet())
        .containsExactly("id", "nr_pedido", "vl_total", "dt_pedido", "ativo", "nota");
    assertThat(first.get("nr_pedido")).isEqualTo("P-0025");
    assertThat(first.get("dt_pedido")).isEqualTo("2026-01-25T10:00:00");
    assertThat(first.get("ativo")).isEqualTo(true);
    assertThat(first.get("nota")).isNull();
    assertThat(items.getLast().get("vl_total")).isEqualTo(210.5d);
  }

  @Test
  void paginationFollowsLimitOffsetAndSignalsTheLastPage() throws Exception {
    String token = token("acme-hml");

    MockHttpServletResponse last = get("/acme/api/pedidos", token, "limit", "10", "offset", "20");
    MockHttpServletResponse max = get("/acme/api/pedidos", token, "limit", "20");

    assertThat(items(last)).hasSize(5);
    assertThat(page(last))
        .containsEntry("hasMore", false)
        .containsEntry("returned", 5L)
        .containsEntry("offset", 20L);
    assertThat(items(max)).hasSize(20);
    assertThat(page(max)).containsEntry("hasMore", true);
    assertThat(
            items(get("/acme/api/pedidos", token, "sort", "vl_total", "limit", "2")).stream()
                .map(i -> i.get("id"))
                .toList())
        .containsExactly(1L, 2L);
    assertThat(
            items(get("/acme/api/pedidos", token, "sort", "-vl_total", "limit", "2")).stream()
                .map(i -> i.get("id"))
                .toList())
        .containsExactly(25L, 24L);
  }

  @Test
  void invalidParametersAreProblemJsonWithOneErrorPerParameter() throws Exception {
    String token = token("acme-hml");

    MockHttpServletResponse response =
        get(
            "/acme/api/pedidos",
            token,
            "limit",
            "21",
            "offset",
            "-1",
            "sort",
            "status",
            "cliente",
            "abc",
            "inventado",
            "1");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    Map<String, Object> problem = json(response);
    assertThat(problem)
        .containsEntry("status", 400L)
        .containsEntry("type", "urn:liberty:problem:validation");
    assertThat(problem.get("title")).isNotNull();
    assertThat(problem.get("requestId")).isEqualTo(response.getHeader("X-Request-Id"));
    assertThat(errors(response))
        .extracting(e -> e.get("parameter") + ":" + e.get("code"))
        .containsExactlyInAnyOrder(
            "limit:out_of_range",
            "offset:out_of_range",
            "sort:sort_not_allowed",
            "cliente:invalid_type",
            "inventado:unknown_parameter");
    assertThat(response.getContentAsString()).doesNotContain("SELECT").doesNotContain("dbo.");
  }

  @Test
  void filtersWorkOverHttp() throws Exception {
    String token = token("acme-hml");

    assertThat(page(get("/acme/api/pedidos", token, "cliente", "2", "limit", "20")).get("returned"))
        .isEqualTo(9L);
    assertThat(
            items(get("/acme/api/pedidos", token, "ids", "3,5,7", "sort", "id")).stream()
                .map(i -> i.get("id"))
                .toList())
        .containsExactly(3L, 5L, 7L);
    assertThat(
            page(get("/acme/api/pedidos", token, "periodo", "2026-01-05,2026-01-07", "limit", "20"))
                .get("returned"))
        .isEqualTo(3L);
    assertThat(
            items(get("/acme/api/pedidos", token, "prefixo", "P-0025")).stream()
                .map(i -> i.get("id"))
                .toList())
        .containsExactly(25L);
    assertThat(
            items(get("/acme/api/pedidos", token, "minimo", "245")).stream()
                .map(i -> i.get("id"))
                .toList())
        .containsExactly(25L);
    MockHttpServletResponse bad = get("/acme/api/pedidos", token, "periodo", "ontem,hoje");
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(errors(bad))
        .extracting(e -> e.get("parameter") + ":" + e.get("code"))
        .containsExactly("periodo:invalid_value");
  }

  // ---------- registro único e rotas ----------

  @Test
  void singleReturnsTheObjectOr404() throws Exception {
    String token = token("acme-hml");

    MockHttpServletResponse found = get("/acme/api/pedidos/P-0003", token);
    MockHttpServletResponse missing = get("/acme/api/pedidos/NAO-EXISTE", token);

    assertThat(found.getStatus()).isEqualTo(200);
    assertThat(json(found).keySet()).containsExactly("id", "nr_pedido", "vl_total", "status");
    assertThat(json(found)).containsEntry("nr_pedido", "P-0003").containsEntry("status", "ABERTO");
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(missing.getContentType()).startsWith("application/problem+json");
    assertThat(json(missing)).containsEntry("type", "urn:liberty:problem:not-found");
  }

  @Test
  void aLiteralRouteBeatsAVariableOne() throws Exception {
    MockHttpServletResponse response = get("/acme/api/pedidos/resumo", token("acme-hml"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(json(response)).containsEntry("origem", "P").containsEntry("total", 1234.5d);
  }

  @Test
  void typedValuesKeepTheirTypesOverHttp() throws Exception {
    Map<String, Object> row = json(get("/acme/api/tipos/1", token("acme-hml")));

    assertThat(row.get("texto")).isEqualTo("olá");
    assertThat(row.get("grande")).isEqualTo(9007199254740993L);
    assertThat(row.get("preco")).isEqualTo(12.34d);
    assertThat(row.get("ativo")).isEqualTo(true);
    assertThat(row.get("data")).isEqualTo("2026-11-06");
    assertThat(row.get("momento")).isEqualTo("2026-11-06T10:15:30.123");
    assertThat(row.get("chave")).isEqualTo("123E4567-E89B-12D3-A456-426614174000");
    assertThat(row.get("anexo")).isEqualTo("AQKr");
    assertThat(json(get("/acme/api/tipos/2", token("acme-hml"))).get("texto")).isNull();
    assertThat(errors(get("/acme/api/tipos/abc", token("acme-hml"))).getFirst())
        .containsEntry("parameter", "id")
        .containsEntry("code", "invalid_type");
  }

  @Test
  void wrongMethodGives405WithAllowAndUnknownPathsGive404() throws Exception {
    String token = token("acme-hml");

    MockHttpServletResponse notAllowed = call(delete("/acme/api/pedidos"), token);
    MockHttpServletResponse putOnSingle = call(put("/acme/api/pedidos/P-0001"), token);

    assertThat(notAllowed.getStatus()).isEqualTo(405);
    assertThat(notAllowed.getHeader("Allow")).isEqualTo("GET, POST");
    assertThat(notAllowed.getContentType()).startsWith("application/problem+json");
    assertThat(putOnSingle.getStatus()).isEqualTo(405);
    assertThat(putOnSingle.getHeader("Allow")).isEqualTo("GET");
    for (String path :
        new String[] {"/acme/api/nao-existe", "/acme/api", "/acme/api/", "/acme/api/pedidos/a/b"}) {
      assertThat(status(path, token)).as(path).isEqualTo(404);
    }
    // caminhos com ".." ou NUL codificados: o firewall recusa (400) e, se passassem, o controlador
    // responderia 404
    for (String path :
        new String[] {
          "/acme/api/pedidos/%2e%2e", "/acme/api/pedidos/..%2Fsegredo", "/acme/api/pedidos/%00"
        }) {
      assertThat(status(path, token)).as(path).isIn(400, 404);
    }
    assertThat(status("/acme/api/pedidos/", token)).as("uma barra final é tolerada").isEqualTo(200);
  }

  @Test
  void writeIntegrationsOnlyAcceptJsonBodies() throws Exception {
    MockHttpServletResponse response =
        call(
            post("/acme/api/pedidos").contentType(MediaType.TEXT_PLAIN).content("{}"),
            token("acme-hml"));

    assertThat(response.getStatus()).isEqualTo(415);
    assertThat(json(response)).containsEntry("type", "urn:liberty:problem:unsupported-media-type");
  }

  // ---------- autorização, ambientes e tenants ----------

  @Test
  void aTokenWithoutTheIntegrationScopeIs403() throws Exception {
    String limited = token("acme-limitado");

    assertThat(status("/acme/api/pedidos/resumo", limited)).isEqualTo(200);
    MockHttpServletResponse denied = get("/acme/api/pedidos", limited);
    assertThat(denied.getStatus()).isEqualTo(403);
    assertThat(denied.getContentType()).startsWith("application/problem+json");
    assertThat(json(denied)).containsEntry("type", "urn:liberty:problem:missing-scope");
    assertThat(denied.getContentAsString()).doesNotContain("pedidos");
  }

  @Test
  void eachEnvironmentServesOnlyWhatIsPublishedThere() throws Exception {
    String hml = token("acme-hml");
    String prd = token("acme-prd");

    assertThat(status("/acme/api/somente-hml", hml)).isEqualTo(200);
    assertThat(status("/acme/api/somente-prd", hml)).isEqualTo(404);
    assertThat(status("/acme/api/somente-prd", prd)).isEqualTo(200);
    assertThat(status("/acme/api/somente-hml", prd)).isEqualTo(404);
    assertThat(status("/acme/api/pedidos", prd)).as("publicado nos dois").isEqualTo(200);
    assertThat(status("/acme/api/pedidos/P-0001", prd)).as("só em HML").isEqualTo(404);
  }

  @Test
  void tenantsNeverSeeEachOthersDataOrRoutes() throws Exception {
    MockHttpServletResponse beta = get("/beta/api/pedidos", token("beta-hml"));

    assertThat(page(beta)).containsEntry("returned", 2L).containsEntry("hasMore", false);
    assertThat(items(beta).stream().map(i -> i.get("nr_pedido")).toList())
        .containsExactly("B-0002", "B-0001");
    assertThat(status("/beta/api/pedidos", token("acme-hml"))).as("slug alheio").isEqualTo(404);
    assertThat(status("/acme/api/pedidos/resumo", token("beta-hml"))).isEqualTo(404);
    assertThat(page(get("/acme/api/pedidos", token("acme-hml"))).get("hasMore")).isEqualTo(true);
  }

  @Test
  void anUnreachableTenantDatabaseIs503NotA500() throws Exception {
    MockHttpServletResponse response = get("/fora/api/pedidos", token("fora-hml"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(json(response)).containsEntry("type", "urn:liberty:problem:tenant-unavailable");
    assertThat(response.getContentAsString()).doesNotContain("127.0.0.1").doesNotContain("jdbc");
  }

  // ---------- publicação ----------

  @Test
  void publishingAndUnpublishingBecomeVisibleWithinTheRefreshInterval() throws Exception {
    String token = token("acme-hml");
    assertThat(status("/acme/api/novo", token)).isEqualTo(404);

    PublicationEntity publication =
        publish(
            acmeId,
            "novo",
            "GET",
            "/novo",
            EnvironmentKind.HML,
            snapshot(
                "novo",
                "GET",
                "/novo",
                "[]",
                select("{\"table\":\"dbo.tb_resumo\",\"columns\":[\"id\"],\"single\":true}")));
    eventually(() -> statusQuietly("/acme/api/novo", token) == 200);

    publication.setActive(false);
    publications.save(publication);
    eventually(() -> statusQuietly("/acme/api/novo", token) == 404);
  }

  @Test
  void invalidatingMakesAPublicationVisibleImmediately() throws Exception {
    String token = token("acme-hml");
    assertThat(status("/acme/api/imediato", token)).isEqualTo(404);

    publish(
        acmeId,
        "imediato",
        "GET",
        "/imediato",
        EnvironmentKind.HML,
        snapshot(
            "imediato",
            "GET",
            "/imediato",
            "[]",
            select("{\"table\":\"dbo.tb_resumo\",\"columns\":[\"id\"],\"single\":true}")));
    routes.invalidate(
        new br.com.liberty.domain.tenancy.TenantScope(
            new br.com.liberty.domain.tenancy.TenantId("acme"), EnvironmentKind.HML));

    assertThat(status("/acme/api/imediato", token))
        .as("sem o escopo, mas a rota já existe")
        .isEqualTo(403);
  }

  private int statusQuietly(String path, String token) {
    try {
      return status(path, token);
    } catch (Exception e) {
      return -1;
    }
  }

  // ---------- identificação das requisições ----------

  @Test
  void everyResponseCarriesARequestIdAndOnlySimpleOnesAreAccepted() throws Exception {
    String token = token("acme-hml");

    MockHttpServletResponse echoed =
        call(
            MockMvcRequestBuilders.get("/acme/api/pedidos").header("X-Request-Id", "req-abc-12345"),
            token);
    MockHttpServletResponse hostile =
        call(
            MockMvcRequestBuilders.get("/acme/api/pedidos")
                .header("X-Request-Id", "x\r\nSet-Cookie: a=b"),
            token);
    MockHttpServletResponse unauthorized =
        call(MockMvcRequestBuilders.get("/acme/api/pedidos"), null);
    MockHttpServletResponse notFound = get("/acme/api/nada", token);

    assertThat(echoed.getHeader("X-Request-Id")).isEqualTo("req-abc-12345");
    assertThat(hostile.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    assertThat(hostile.getHeader("Set-Cookie")).isNull();
    assertThat(unauthorized.getStatus()).isEqualTo(401);
    assertThat(unauthorized.getHeader("X-Request-Id")).isNotBlank();
    assertThat(json(notFound).get("requestId")).isEqualTo(notFound.getHeader("X-Request-Id"));
  }

  // ---------- fuzzing: nenhuma entrada vira SQL ----------

  @Test
  void fuzzedParametersAndPathsNeverBreakTheDatabaseOrLeakErrors() throws Exception {
    String token = token("acme-hml");
    long pedidosBefore = count(ACME_DB, "SELECT COUNT(*) FROM dbo.tb_pedido");
    long tablesBefore = count(ACME_DB, "SELECT COUNT(*) FROM sys.tables");
    String alphabet = "abcXYZ_09 ;'\"[]-/*%\\.,\n\t\u0000é中()=@#$&|:!?<>";
    String[] payloads = {
      "' OR 1=1 --",
      "1; DROP TABLE dbo.tb_pedido;--",
      "x' UNION SELECT name FROM sys.tables--",
      "%",
      "_",
      "]",
      "[",
      "--",
      "\\",
      "1,2); DELETE FROM dbo.tb_pedido;--",
      "P-0001' OR '1'='1",
      "2026-01-01'; DROP TABLE dbo.tb_resumo;--"
    };
    String[] names = {
      "cliente", "status", "prefixo", "ids", "periodo", "minimo", "limit", "offset", "sort"
    };
    Random random = new Random(11);
    int answered = 0;
    for (int i = 0; i < 400; i++) {
      var request = MockMvcRequestBuilders.get("/acme/api/pedidos");
      int params = 1 + random.nextInt(3);
      for (int p = 0; p < params; p++) {
        String value =
            i < payloads.length * 3 ? payloads[i % payloads.length] : random(random, alphabet);
        request.param(names[random.nextInt(names.length)], value);
      }
      if (random.nextInt(8) == 0) {
        request.param(random(random, alphabet), random(random, alphabet));
      }
      MockHttpServletResponse response = call(request, token);
      assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 400);
      assertThat(response.getContentAsString())
          .doesNotContain("SQLServer")
          .doesNotContain("Exception")
          .doesNotContain("sys.tables");
      answered++;
    }
    for (int i = 0; i < 200; i++) {
      String segment = i < payloads.length ? payloads[i] : random(random, alphabet);
      MockHttpServletResponse response =
          call(
              MockMvcRequestBuilders.get(
                  "/acme/api/pedidos/"
                      + UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8)),
              token);
      assertThat(response.getStatus()).as(segment).isIn(200, 400, 404);
      assertThat(response.getContentAsString())
          .doesNotContain("SQLServer")
          .doesNotContain("Exception");
      answered++;
    }

    assertThat(answered).isEqualTo(600);
    assertThat(count(ACME_DB, "SELECT COUNT(*) FROM dbo.tb_pedido"))
        .isEqualTo(pedidosBefore)
        .isEqualTo(25);
    assertThat(count(ACME_DB, "SELECT COUNT(*) FROM sys.tables")).isEqualTo(tablesBefore);
    assertThat(count(ACME_DB, "SELECT COUNT(*) FROM dbo.tb_resumo")).isEqualTo(1);
  }

  private static String random(Random random, String alphabet) {
    StringBuilder sb = new StringBuilder();
    for (int n = random.nextInt(24); n >= 0; n--) {
      sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return sb.toString();
  }
}
