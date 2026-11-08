package br.com.liberty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.IdempotencyEntity;
import br.com.liberty.infrastructure.persistence.IdempotencyRepository;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * Contrato da API de escrita de ponta a ponta: token real, rotas publicadas no core, banco de
 * tenant real e idempotência persistida no core.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class WriteContractTest {

  private static final CoreTestDatabase.Scratch ACME_DB = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch CORE_DB = CoreTestDatabase.create();
  private static HttpServer vault;

  static {
    try {
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/liberty/tenants/",
          exchange -> {
            byte[] body =
                ("{\"data\":{\"data\":{\"password\":\""
                        + ACME_DB.password()
                        + "\"},\"metadata\":{}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      vault.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    var core = CORE_DB;
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
  @Autowired IdempotencyRepository idempotency;
  @Autowired PasswordEncoder encoder;

  private static boolean seeded;
  private static long acmeId;

  // ---------- dados ----------

  private static void sql(String... statements) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(ACME_DB.url(), ACME_DB.user(), ACME_DB.password());
        Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  private static long count(String table) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(ACME_DB.url(), ACME_DB.user(), ACME_DB.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String j(String quoted) {
    return quoted.replace('\'', '"');
  }

  private static final String LOTE =
      "{'table':'dbo.tb_lote','arrayPath':'/lotes','foreignKey':'item_id','fields':[{'column':'codigo','source':'/codigo'}]}";
  private static final String ITEM =
      "{'table':'dbo.tb_item','arrayPath':'/itens','primaryKey':'id','foreignKey':'pedido_id','fields':["
          + "{'column':'produto','source':'/produto'},{'column':'qtd','source':'/qtd'},{'column':'vl','source':'/valor'}],"
          + "'children':["
          + LOTE
          + "]}";
  private static final String PEDIDO =
      "{'table':'dbo.tb_pedido','primaryKey':'id','fields':[{'column':'numero','source':'/numero'},"
          + "{'column':'cliente_id','source':'/clienteId'},{'column':'vl_total','source':'/total'}],'children':["
          + ITEM
          + "]}";
  private static final String PEDIDO_SCHEMA =
      "{'type':'object','required':['numero','clienteId','total'],'additionalProperties':false,"
          + "'properties':{'numero':{'type':'string','maxLength':30},'clienteId':{'type':'integer'},"
          + "'total':{'type':'number'},'itens':{'type':'array','maxItems':100}}}";

  private static String snapshot(
      String key,
      String method,
      String route,
      String params,
      String schema,
      String operation,
      String extraRequest,
      String extra) {
    return j(
        "{'schemaVersion':1,'integration':{'key':'"
            + key
            + "','name':'"
            + key
            + "','method':'"
            + method
            + "','route':'"
            + route
            + "'},'request':{'params':"
            + params
            + ",'bodySchema':"
            + schema
            + extraRequest
            + "},'operation':"
            + operation
            + extra
            + "}");
  }

  private void publish(String key, String method, String route, String json) {
    IntegrationEntity integration =
        integrations.save(new IntegrationEntity(acmeId, key, key, method, route));
    IntegrationVersionEntity version =
        versions.save(
            new IntegrationVersionEntity(
                acmeId, integration.getId(), 1, VersionState.PUBLISHED, json, new byte[32], "ana"));
    publications.save(
        new PublicationEntity(
            acmeId, integration.getId(), version.getId(), EnvironmentKind.HML, true, "bia"));
  }

  private void client(String id, String scopes) {
    clients.save(
        new ApiClientEntity(
            acmeId, id, encoder.encode("segredo-" + id), EnvironmentKind.HML, scopes));
  }

  @BeforeEach
  void seed() throws Exception {
    if (seeded) {
      return;
    }
    seeded = true;
    sql(
        "CREATE TABLE dbo.tb_pedido (id BIGINT IDENTITY PRIMARY KEY, numero VARCHAR(30) NOT NULL UNIQUE,"
            + " cliente_id INT NOT NULL, vl_total DECIMAL(18,2) NOT NULL)",
        "CREATE TABLE dbo.tb_item (id BIGINT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL REFERENCES dbo.tb_pedido(id),"
            + " produto VARCHAR(30) NOT NULL, qtd INT NOT NULL CHECK (qtd > 0), vl DECIMAL(18,2) NOT NULL)",
        "CREATE TABLE dbo.tb_lote (id BIGINT IDENTITY PRIMARY KEY, item_id BIGINT NOT NULL REFERENCES dbo.tb_item(id),"
            + " codigo VARCHAR(20) NOT NULL)",
        "CREATE TABLE dbo.tb_cliente (codigo VARCHAR(20) PRIMARY KEY, nome NVARCHAR(50) NOT NULL, email VARCHAR(80) NULL)",
        "CREATE TABLE dbo.tb_log (id INT IDENTITY PRIMARY KEY, pedido_id BIGINT NOT NULL)",
        "CREATE TRIGGER dbo.trg_pedido_log ON dbo.tb_pedido AFTER INSERT AS BEGIN INSERT INTO dbo.tb_log (pedido_id)"
            + " SELECT id FROM inserted END");
    acmeId = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    connections.save(
        new TenantConnectionEntity(
            acmeId,
            EnvironmentKind.HML,
            ACME_DB.url(),
            ACME_DB.user(),
            "liberty/tenants/acme/hml",
            4));
    client(
        "acme-hml",
        "integration:execute:pedido-criar integration:execute:pedido-idem"
            + " integration:execute:cliente-upsert integration:execute:cliente-atualizar"
            + " integration:execute:pedido-script");
    client("acme-leitor", "integration:execute:cliente-upsert");

    publish(
        "pedido-criar",
        "POST",
        "/pedidos",
        snapshot(
            "pedido-criar",
            "POST",
            "/pedidos",
            "[]",
            j(PEDIDO_SCHEMA),
            "{'type':'INSERT','write':{'root':" + PEDIDO + "}}",
            "",
            ""));
    publish(
        "pedido-idem",
        "POST",
        "/pedidos-idem",
        snapshot(
            "pedido-idem",
            "POST",
            "/pedidos-idem",
            "[]",
            j(PEDIDO_SCHEMA),
            "{'type':'INSERT','write':{'root':" + PEDIDO + "}}",
            ",'maxBodyBytes':600",
            ",'idempotency':{'required':true},'response':{'successStatus':200}"));
    String before =
        "function beforeExecute(i) { const b = i.body; b.numero = b.numero.toUpperCase();"
            + " if (b.clienteId === 99) fail(422, `cliente bloqueado`);"
            + " if (b.numero === `LOOP`) { while (true) {} }"
            + " if (b.numero === `ERRO`) { throw new Error(`segredo interno`); }"
            + " return b; }";
    String after =
        "function afterExecute(i) { if (i.body.clienteId === 98) fail(409, `regra de negocio`);"
            + " if (i.body.clienteId === 97) queue.publish(`webhook`, { url: `https://api.exemplo.com/h`, body: { pedido: i.result.key.id } });"
            + " if (i.body.clienteId === 96) { queue.publish(`webhook`, { url: `https://api.exemplo.com/h` }); fail(409, `desfaz`); }"
            + " const q = db.named(`qtd_itens`, { id: i.result.key.id });"
            + " return Object.assign({}, i.result, { itensGravados: q[0].n, origem: i.params.x }); }";
    publish(
        "pedido-script",
        "POST",
        "/pedidos-script",
        snapshot(
            "pedido-script",
            "POST",
            "/pedidos-script",
            "[]",
            j(PEDIDO_SCHEMA),
            "{'type':'INSERT','write':{'root':" + PEDIDO + "}}",
            "",
            ",'scripts':{'beforeExecute':\""
                + before
                + "\",'afterExecute':\""
                + after
                + "\"},'namedQueries':[{'name':'qtd_itens','sql':'SELECT COUNT(*) AS n FROM dbo.tb_item WHERE pedido_id = :id','params':['id']}]"));
    String cliente =
        "{'table':'dbo.tb_cliente','keyColumns':['codigo'],'fields':[{'column':'codigo','param':'codigo'},"
            + "{'column':'nome','source':'/nome'},{'column':'email','source':'/email'}]}";
    String clienteParams = "[{'name':'codigo','in':'path','type':'string'}]";
    String clienteSchema =
        "{'type':'object','additionalProperties':false,'properties':{'nome':{'type':'string'},'email':{'type':['string','null']}}}";
    publish(
        "cliente-upsert",
        "PUT",
        "/clientes/{codigo}",
        snapshot(
            "cliente-upsert",
            "PUT",
            "/clientes/{codigo}",
            clienteParams,
            clienteSchema,
            "{'type':'UPSERT','write':{'root':" + cliente + "}}",
            "",
            ""));
    publish(
        "cliente-atualizar",
        "PATCH",
        "/clientes/{codigo}",
        snapshot(
            "cliente-atualizar",
            "PATCH",
            "/clientes/{codigo}",
            clienteParams,
            clienteSchema,
            "{'type':'UPDATE','write':{'root':" + cliente + "}}",
            "",
            ""));
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

  private MockHttpServletResponse send(
      MockHttpServletRequestBuilder request, String body, String key) throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acme-hml"));
    request.contentType(MediaType.APPLICATION_JSON).content(body.replace('\'', '"'));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse postPedido(String body) throws Exception {
    return send(post("/acme/api/pedidos"), body, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> json(MockHttpServletResponse response) throws Exception {
    return JSONObjectUtils.parse(response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static String pedido(String numero, String itens) {
    return "{'numero':'" + numero + "','clienteId':7,'total':150.75,'itens':[" + itens + "]}";
  }

  private static final String ITENS_OK =
      "{'produto':'A','qtd':2,'valor':10.5,'lotes':[{'codigo':'L1'},{'codigo':'L2'}]},"
          + "{'produto':'B','qtd':1,'valor':99.99,'lotes':[{'codigo':'L3'}]}";

  // ---------- master-detail ----------

  @Test
  void threeLevelsAreCreatedTogether() throws Exception {
    long before = count("dbo.tb_lote");

    MockHttpServletResponse response = postPedido(pedido("HTTP-1", ITENS_OK));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    assertThat(response.getContentType()).startsWith("application/json");
    Map<String, Object> body = json(response);
    assertThat(body).containsKeys("key", "rows");
    assertThat(count("dbo.tb_lote")).isEqualTo(before + 3);
  }

  @Test
  void aFailureDeepInTheTreeLeavesNothingBehindAndLeaksNoDatabaseDetail() throws Exception {
    long pedidos = count("dbo.tb_pedido");
    long itens = count("dbo.tb_item");
    long lotes = count("dbo.tb_lote");

    MockHttpServletResponse response =
        postPedido(
            pedido(
                "HTTP-2",
                "{'produto':'A','qtd':1,'valor':1,'lotes':[{'codigo':'ok'}]},"
                    + "{'produto':'B','qtd':1,'valor':1,'lotes':[{'codigo':'um-codigo-bem-maior-que-vinte-caracteres'}]}"));

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString())
        .doesNotContain("tb_lote")
        .doesNotContain("truncated")
        .doesNotContain("dbo");
    assertThat(count("dbo.tb_pedido")).isEqualTo(pedidos);
    assertThat(count("dbo.tb_item")).isEqualTo(itens);
    assertThat(count("dbo.tb_lote")).isEqualTo(lotes);
  }

  @Test
  void bodyValidationErrorsAreListedPerField() throws Exception {
    MockHttpServletResponse response =
        postPedido("{'numero':5,'clienteId':'x','extra':1,'itens':[{'produto':'A'}]}");

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(json(response)).containsEntry("type", "urn:liberty:problem:validation");
    assertThat(response.getContentAsString()).contains("/numero").contains("/total");
  }

  // ---------- transporte ----------

  @Test
  void onlyJsonBodiesAreAcceptedAndMalformedOnesAreRefused() throws Exception {
    var plain =
        post("/acme/api/pedidos")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acme-hml"))
            .contentType(MediaType.TEXT_PLAIN)
            .content("{}");
    var absent =
        post("/acme/api/pedidos").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acme-hml"));

    assertThat(mvc.perform(plain).andReturn().getResponse().getStatus()).isEqualTo(415);
    assertThat(mvc.perform(absent).andReturn().getResponse().getStatus()).isEqualTo(415);
    assertThat(postPedido("{").getStatus()).isEqualTo(400);
    assertThat(postPedido("").getStatus()).isEqualTo(400);
  }

  @Test
  void theBodySizeLimitOfTheIntegrationIsEnforced() throws Exception {
    String big = pedido("BIG", "{'produto':'" + "x".repeat(700) + "','qtd':1,'valor':1}");

    MockHttpServletResponse response = send(post("/acme/api/pedidos-idem"), big, "grande-1");

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(json(response)).containsEntry("type", "urn:liberty:problem:payload-too-large");
  }

  @Test
  void writeScopesAreEnforcedPerIntegration() throws Exception {
    var request =
        post("/acme/api/pedidos")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acme-leitor"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}");

    assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(403);
  }

  // ---------- idempotência ----------

  @Test
  void repeatingTheSameKeyReplaysTheResponseWithoutDuplicating() throws Exception {
    String body = pedido("IDEM-1", ITENS_OK);

    MockHttpServletResponse first = send(post("/acme/api/pedidos-idem"), body, "chave-1");
    long pedidos = count("dbo.tb_pedido");
    long logs = count("dbo.tb_log");
    MockHttpServletResponse repeat = send(post("/acme/api/pedidos-idem"), body, "chave-1");

    assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(200);
    assertThat(first.getHeader("Idempotent-Replayed")).isNull();
    assertThat(repeat.getStatus()).isEqualTo(200);
    assertThat(repeat.getHeader("Idempotent-Replayed")).isEqualTo("true");
    assertThat(json(repeat)).isEqualTo(json(first));
    assertThat(count("dbo.tb_pedido")).isEqualTo(pedidos);
    assertThat(count("dbo.tb_log")).isEqualTo(logs);
    var stored =
        idempotency.findById(
            new IdempotencyEntity.Key(
                acmeId,
                integrations
                    .findByTenantIdAndHttpMethodAndRoutePattern(acmeId, "POST", "/pedidos-idem")
                    .orElseThrow()
                    .getId(),
                "chave-1"));
    assertThat(stored).isPresent();
    assertThat(stored.get().getStatus()).isEqualTo("DONE");
    assertThat(stored.get().getHttpStatus()).isEqualTo((short) 200);
  }

  @Test
  void theSameKeyWithAnotherBodyConflictsAndAMissingKeyIsRefused() throws Exception {
    send(post("/acme/api/pedidos-idem"), pedido("IDEM-2", ITENS_OK), "chave-2");

    MockHttpServletResponse reuse =
        send(post("/acme/api/pedidos-idem"), pedido("IDEM-3", ITENS_OK), "chave-2");
    MockHttpServletResponse missing =
        send(post("/acme/api/pedidos-idem"), pedido("IDEM-4", ITENS_OK), null);

    assertThat(reuse.getStatus()).isEqualTo(409);
    assertThat(json(reuse)).containsEntry("type", "urn:liberty:problem:idempotency-key-reuse");
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(missing.getContentAsString()).contains("Idempotency-Key");
  }

  @Test
  void parallelRequestsWithTheSameKeyWriteOnce() throws Exception {
    String body = pedido("PAR-1", ITENS_OK);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      for (int i = 0; i < 6; i++) {
        results.add(
            pool.submit(() -> send(post("/acme/api/pedidos-idem"), body, "paralela").getStatus()));
      }
      for (Future<Integer> result : results) {
        assertThat(result.get()).isIn(200, 409);
      }
    }

    assertThat(count("dbo.tb_pedido WHERE numero = 'PAR-1'")).isEqualTo(1);
  }

  @Test
  void aRolledBackWriteReleasesItsKey() throws Exception {
    String bad = pedido("REL-1", "{'produto':'A','qtd':0,'valor':1}");
    MockHttpServletResponse failed = send(post("/acme/api/pedidos-idem"), bad, "liberada");

    MockHttpServletResponse ok =
        send(
            post("/acme/api/pedidos-idem"),
            pedido("REL-1", "{'produto':'A','qtd':1,'valor':1}"),
            "liberada");

    assertThat(failed.getStatus()).isEqualTo(409);
    assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
  }

  // ---------- UPSERT e UPDATE ----------

  @Test
  void upsertCreatesThenUpdatesAndUpdateOfAMissingKeyIs404() throws Exception {
    MockHttpServletResponse created =
        send(put("/acme/api/clientes/C-9"), "{'nome':'Ana','email':'a@x'}", null);
    MockHttpServletResponse updated =
        send(put("/acme/api/clientes/C-9"), "{'nome':'Ana Maria'}", null);
    MockHttpServletResponse patched = send(patch("/acme/api/clientes/C-9"), "{'email':null}", null);
    MockHttpServletResponse missing = send(patch("/acme/api/clientes/NAO"), "{'nome':'x'}", null);

    assertThat(created.getStatus()).isEqualTo(201);
    assertThat(updated.getStatus()).isEqualTo(200);
    assertThat(patched.getStatus()).as(patched.getContentAsString()).isEqualTo(200);
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(
            count("dbo.tb_cliente WHERE codigo = 'C-9' AND nome = N'Ana Maria' AND email IS NULL"))
        .isEqualTo(1);
  }

  // ---------- scripts ----------

  private MockHttpServletResponse postScript(String body) throws Exception {
    return send(post("/acme/api/pedidos-script"), body, null);
  }

  @Test
  void scriptsEnrichTheBodyAndTheResponseInsideTheTransaction() throws Exception {
    MockHttpServletResponse response = postScript(pedido("script-ok", ITENS_OK));

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    assertThat(json(response)).containsEntry("itensGravados", 2L);
    assertThat(count("dbo.tb_pedido WHERE numero = 'SCRIPT-OK'")).isEqualTo(1);
  }

  @Test
  void aScriptRefusalBeforeOrAfterTheWriteLeavesNothingBehind() throws Exception {
    long pedidos = count("dbo.tb_pedido");
    MockHttpServletResponse before =
        postScript(pedido("blk", ITENS_OK).replace("'clienteId':7", "'clienteId':99"));
    MockHttpServletResponse after =
        postScript(pedido("rollback", ITENS_OK).replace("'clienteId':7", "'clienteId':98"));

    assertThat(before.getStatus()).isEqualTo(422);
    assertThat(json(before)).containsEntry("type", "urn:liberty:problem:script-rejected");
    assertThat(before.getContentAsString()).contains("cliente bloqueado");
    assertThat(after.getStatus()).isEqualTo(409);
    assertThat(count("dbo.tb_pedido")).isEqualTo(pedidos);
  }

  @Test
  void queueMessagesFromAScriptAreWrittenOnlyWhenTheWriteCommits() throws Exception {
    String before = coreScalar("SELECT COUNT(*) FROM int_queue_message");
    MockHttpServletResponse rolledBack =
        postScript(pedido("fila-1", ITENS_OK).replace("'clienteId':7", "'clienteId':96"));
    String afterRollback = coreScalar("SELECT COUNT(*) FROM int_queue_message");
    MockHttpServletResponse committed =
        postScript(pedido("fila-2", ITENS_OK).replace("'clienteId':7", "'clienteId':97"));

    assertThat(rolledBack.getStatus()).isEqualTo(409);
    assertThat(afterRollback).as("a escrita desfeita não publica").isEqualTo(before);
    assertThat(committed.getStatus()).as(committed.getContentAsString()).isEqualTo(201);
    assertThat(coreScalar("SELECT COUNT(*) FROM int_queue_message"))
        .isEqualTo(String.valueOf(Long.parseLong(before) + 1));
    assertThat(
            coreScalar(
                "SELECT TOP 1 CONCAT(t.slug, '|', m.environment, '|', m.queue_name, '|', m.status)"
                    + " FROM int_queue_message m JOIN int_tenant t ON t.id = m.tenant_id ORDER BY m.id DESC"))
        .isEqualTo("acme|HML|webhook|READY");
  }

  @Test
  void runawayAndFailingScriptsAreContainedWithoutLeakingDetails() throws Exception {
    MockHttpServletResponse loop = postScript(pedido("loop", ITENS_OK));
    MockHttpServletResponse broken = postScript(pedido("erro", ITENS_OK));
    MockHttpServletResponse healthy = postScript(pedido("depois", ITENS_OK));

    assertThat(loop.getStatus()).isEqualTo(504);
    assertThat(json(loop)).containsEntry("type", "urn:liberty:problem:script-timeout");
    assertThat(broken.getStatus()).isEqualTo(500);
    assertThat(broken.getContentAsString()).doesNotContain("segredo interno");
    assertThat(healthy.getStatus()).isEqualTo(201);
  }

  // ---------- auditoria e métricas ----------

  private static String coreScalar(String sql) throws SQLException {
    try (Connection c =
            DriverManager.getConnection(CORE_DB.url(), CORE_DB.user(), CORE_DB.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static void eventuallyTrue(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long limit = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < limit && !condition.getAsBoolean()) {
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Test
  void everyExecutionIsAuditedWithoutPayloadsAndMeasured() throws Exception {
    postPedido(pedido("AUD-1", ITENS_OK));
    postPedido("{'numero':5,'email':'ana@exemplo.com'}");

    eventuallyTrue(
        () -> {
          try {
            return "2"
                .equals(
                    coreScalar(
                        "SELECT COUNT(*) FROM int_audit_execution WHERE integration_key = 'pedido-criar'"));
          } catch (SQLException e) {
            return false;
          }
        });

    assertThat(
            coreScalar(
                "SELECT COUNT(*) FROM int_audit_execution WHERE integration_key = 'pedido-criar' AND status_code = 201"))
        .isEqualTo("1");
    assertThat(
            coreScalar(
                "SELECT error_code FROM int_audit_execution WHERE integration_key = 'pedido-criar' AND status_code = 400"))
        .isEqualTo("validation");
    assertThat(
            coreScalar(
                "SELECT client_id FROM int_audit_execution WHERE status_code = 201 AND integration_key = 'pedido-criar'"))
        .isEqualTo("acme-hml");
    assertThat(
            coreScalar(
                "SELECT COUNT(*) FROM int_audit_execution WHERE route = '/pedidos' AND request_id <> '-' AND duration_ms >= 0"))
        .isNotEqualTo("0");
    assertThat(
            coreScalar(
                "SELECT db_duration_ms FROM int_audit_execution WHERE status_code = 201 AND integration_key = 'pedido-criar'"))
        .isNotNull();
    assertThat(
            coreScalar(
                "SELECT COUNT(*) FROM int_audit_execution WHERE CONCAT(error_message, route) LIKE '%ana@%' OR error_message LIKE '%AUD-1%'"))
        .isEqualTo("0");
  }

  @Test
  void prometheusMetricsAreOnlyForTheTechnicalAccount() throws Exception {
    postPedido(pedido("MET-1", ITENS_OK));
    String basic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("metrics:teste-metricas".getBytes(StandardCharsets.UTF_8));
    String wrong =
        "Basic "
            + Base64.getEncoder().encodeToString("metrics:errada".getBytes(StandardCharsets.UTF_8));

    var anonymous = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse();
    var bad =
        mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, wrong))
            .andReturn()
            .getResponse();
    var ok =
        mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, basic))
            .andReturn()
            .getResponse();
    var clientToken =
        mvc.perform(
                get("/actuator/prometheus")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("acme-hml")))
            .andReturn()
            .getResponse();

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(bad.getStatus()).isEqualTo(401);
    assertThat(clientToken.getStatus()).isEqualTo(401);
    assertThat(ok.getStatus()).isEqualTo(200);
    String text = ok.getContentAsString();
    assertThat(text)
        .contains("liberty_integration_duration_seconds_count")
        .contains("integration=\"pedido-criar\"")
        .contains("liberty_audit_dropped_total")
        .contains("liberty_audit_queue_size")
        .contains("liberty_script_workers")
        .contains("application=\"liberty\"");
  }
}
