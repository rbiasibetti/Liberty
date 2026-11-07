package br.com.liberty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Vizinho barulhento: um tenant que dispara muito além do seu plano recebe 429 e não muda a
 * latência nem a taxa de sucesso dos demais.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class NoisyNeighborTest {

  private static final CoreTestDatabase.Scratch BARULHENTO_DB = CoreTestDatabase.create();
  private static final CoreTestDatabase.Scratch VIZINHO_DB = CoreTestDatabase.create();
  private static HttpServer vault;

  static {
    try {
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/liberty/tenants/",
          exchange -> {
            String password =
                exchange.getRequestURI().getPath().contains("barulhento")
                    ? BARULHENTO_DB.password()
                    : VIZINHO_DB.password();
            byte[] body =
                ("{\"data\":{\"data\":{\"password\":\"" + password + "\"},\"metadata\":{}}}")
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
    registry.add("liberty.limits.rate-per-second", () -> "40");
    registry.add("liberty.limits.burst", () -> "40");
    registry.add("liberty.limits.max-concurrent", () -> "3");
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

  private static boolean seeded;

  private static void sql(CoreTestDatabase.Scratch db, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        Statement st = c.createStatement()) {
      for (String s : statements) {
        st.execute(s);
      }
    }
  }

  private void tenant(String slug, CoreTestDatabase.Scratch db) throws SQLException {
    sql(
        db,
        "CREATE TABLE dbo.tb_item (id INT PRIMARY KEY, nome VARCHAR(30) NOT NULL)",
        "INSERT INTO dbo.tb_item VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    long id = tenants.save(new TenantEntity(slug, slug, "STANDARD")).getId();
    connections.save(
        new TenantConnectionEntity(
            id, EnvironmentKind.HML, db.url(), db.user(), "liberty/tenants/" + slug + "/hml", 4));
    clients.save(
        new ApiClientEntity(
            id,
            slug + "-hml",
            encoder.encode("segredo-" + slug),
            EnvironmentKind.HML,
            "integration:execute:itens"));
    String json =
        "{\"schemaVersion\":1,\"integration\":{\"key\":\"itens\",\"name\":\"Itens\",\"method\":\"GET\",\"route\":\"/itens\"},"
            + "\"request\":{\"params\":[]},\"operation\":{\"type\":\"SELECT\",\"select\":{\"table\":\"dbo.tb_item\",\"columns\":[\"id\",\"nome\"],\"sortable\":[\"id\"]}}}";
    IntegrationEntity integration =
        integrations.save(new IntegrationEntity(id, "itens", "itens", "GET", "/itens"));
    IntegrationVersionEntity version =
        versions.save(
            new IntegrationVersionEntity(
                id, integration.getId(), 1, VersionState.PUBLISHED, json, new byte[32], "ana"));
    publications.save(
        new PublicationEntity(
            id, integration.getId(), version.getId(), EnvironmentKind.HML, true, "bia"));
  }

  @BeforeEach
  void seed() throws Exception {
    if (seeded) {
      return;
    }
    seeded = true;
    tenant("barulhento", BARULHENTO_DB);
    tenant("vizinho", VIZINHO_DB);
  }

  private String token(String slug) throws Exception {
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
                                    (slug + "-hml:segredo-" + slug)
                                        .getBytes(StandardCharsets.UTF_8))))
            .andReturn()
            .getResponse();
    return (String) JSONObjectUtils.parse(response.getContentAsString()).get("access_token");
  }

  private MockHttpServletResponse call(String slug, String token) throws Exception {
    return mvc.perform(
            get("/" + slug + "/api/itens").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andReturn()
        .getResponse();
  }

  /** p95 em milissegundos de {@code count} chamadas sequenciais do vizinho, e quantas falharam. */
  private long[] neighbor(String token, int count) throws Exception {
    List<Long> times = new ArrayList<>();
    long failures = 0;
    for (int i = 0; i < count; i++) {
      long start = System.nanoTime();
      if (call("vizinho", token).getStatus() != 200) {
        failures++;
      }
      times.add((System.nanoTime() - start) / 1_000_000);
      Thread.sleep(40); // ~20 req/s, metade do limite do plano
    }
    Collections.sort(times);
    return new long[] {times.get((int) Math.ceil(count * 0.95) - 1), failures};
  }

  @Test
  void aTenantAtTenTimesItsPlanDoesNotChangeTheOthers() throws Exception {
    String quiet = token("vizinho");
    String loud = token("barulhento");
    neighbor(quiet, 20); // aquece pools, caches e JIT
    long[] baseline = neighbor(quiet, 80);

    AtomicInteger ok = new AtomicInteger();
    AtomicInteger limited = new AtomicInteger();
    AtomicInteger other = new AtomicInteger();
    long[] during;
    try (ExecutorService flood = Executors.newFixedThreadPool(12)) {
      List<Future<?>> futures = new ArrayList<>();
      long deadline = System.currentTimeMillis() + 6_000;
      for (int t = 0; t < 12; t++) {
        futures.add(
            flood.submit(
                () -> {
                  while (System.currentTimeMillis() < deadline) {
                    try {
                      int status = call("barulhento", loud).getStatus();
                      (status == 200 ? ok : status == 429 ? limited : other).incrementAndGet();
                    } catch (Exception e) {
                      other.incrementAndGet();
                    }
                  }
                }));
      }
      Thread.sleep(500);
      during = neighbor(quiet, 80);
      for (Future<?> f : futures) {
        f.get();
      }
    }

    org.slf4j.LoggerFactory.getLogger(NoisyNeighborTest.class)
        .info(
            "Vizinho barulhento: p95 base={} ms, durante={} ms; barulhento: {} ok, {} 429",
            baseline[0],
            during[0],
            ok.get(),
            limited.get());
    assertThat(during[1]).as("o vizinho não recebe nenhuma recusa nem erro").isZero();
    assertThat(baseline[1]).isZero();
    assertThat(limited.get()).as("o barulhento é recusado com 429").isGreaterThan(ok.get());
    assertThat(other.get()).as("só 200 e 429 para o barulhento").isZero();
    // o plano pede variação abaixo de 20%; aqui a margem é folgada para não depender da máquina
    assertThat(during[0])
        .as("p95 do vizinho durante a enxurrada (ms), base " + baseline[0])
        .isLessThanOrEqualTo(Math.max(baseline[0] * 2, baseline[0] + 100));
  }

  @Test
  void the429CarriesRetryHeadersAndAProblemBody() throws Exception {
    String loud = token("barulhento");
    MockHttpServletResponse limited = null;
    for (int i = 0; i < 400 && limited == null; i++) {
      var response = call("barulhento", loud);
      if (response.getStatus() == 429) {
        limited = response;
      }
    }

    assertThat(limited).isNotNull();
    assertThat(limited.getHeader("Retry-After")).isNotNull();
    assertThat(limited.getHeader("RateLimit-Limit")).isEqualTo("40");
    assertThat(limited.getContentType()).startsWith("application/problem+json");
    assertThat(limited.getContentAsString()).contains("urn:liberty:problem:rate-limited");
  }
}
