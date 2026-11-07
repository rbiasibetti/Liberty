package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Acesso cruzado entre tenants em TODOS os endpoints da API de administração: a lista de endpoints
 * vem da especificação OpenAPI (que o {@code AdminApiSpecTest} obriga a estar completa), então um
 * endpoint novo entra aqui sem ninguém lembrar. Quem só tem papéis no tenant B nunca alcança o
 * tenant A; sistemas e anônimos nunca alcançam rota de usuário.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CrossTenantAccessTest {

  private static final Path SPEC = Path.of("..", "..", "docs", "openapi", "admin-api.yaml");
  private static OidcStub oidc;

  static {
    try {
      oidc = new OidcStub();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    var db = CoreTestDatabase.create();
    registry.add("spring.datasource.url", db::url);
    registry.add("spring.datasource.username", db::user);
    registry.add("spring.datasource.password", db::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("liberty.security.oidc.issuer", () -> oidc.issuer);
  }

  @AfterAll
  static void stop() {
    oidc.close();
  }

  @Autowired MockMvc mvc;
  @Autowired TenantRepository tenants;
  @Autowired UserRoleRepository roles;
  @Autowired ApiClientRepository clients;
  @Autowired PasswordEncoder encoder;

  private static boolean seeded;

  @BeforeEach
  void seed() {
    if (seeded) {
      return;
    }
    seeded = true;
    long acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    long beta = tenants.save(new TenantEntity("beta", "Beta", "STANDARD")).getId();
    roles.save(new UserRoleEntity("dono-da-beta", beta, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("dono-da-beta", beta, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("dono-da-beta", beta, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("dono-da-acme", acme, TenantRole.PUBLISHER));
    clients.save(
        new ApiClientEntity(
            beta,
            "beta-hml",
            encoder.encode("segredo-beta"),
            EnvironmentKind.HML,
            "integration:execute:x"));
  }

  /** Um endpoint da especificação com os parâmetros de caminho já preenchidos. */
  private record Endpoint(HttpMethod method, String template, String path) {
    boolean tenantScoped() {
      return template.startsWith("/api/v1/tenants/{slug}/")
          || template.startsWith("/api/v1/platform/");
    }
  }

  private static final Map<String, String> SAMPLE =
      Map.ofEntries(
          Map.entry("slug", "acme"),
          Map.entry("key", "pedidos"),
          Map.entry("no", "1"),
          Map.entry("env", "HML"),
          Map.entry("table", "dbo.tb_pedido"),
          Map.entry("clientId", "erp"),
          Map.entry("sub", "alguem"),
          Map.entry("role", "VIEWER"));

  @SuppressWarnings("unchecked")
  private static List<Endpoint> endpoints() throws IOException {
    try (InputStream in = Files.newInputStream(SPEC)) {
      Map<String, Object> doc = new Yaml().load(in);
      List<Endpoint> found = new ArrayList<>();
      ((Map<String, Map<String, Object>>) doc.get("paths"))
          .forEach(
              (template, item) ->
                  item.keySet().stream()
                      .filter(k -> Set.of("get", "post", "put", "patch", "delete").contains(k))
                      .forEach(
                          k -> {
                            String path = template;
                            for (var sample : SAMPLE.entrySet()) {
                              path = path.replace("{" + sample.getKey() + "}", sample.getValue());
                            }
                            found.add(
                                new Endpoint(HttpMethod.valueOf(k.toUpperCase()), template, path));
                          }));
      return found;
    }
  }

  private int status(Endpoint endpoint, String bearer) throws Exception {
    var builder = request(endpoint.method(), endpoint.path());
    if (bearer != null) {
      builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    if (endpoint.method() != HttpMethod.GET && endpoint.method() != HttpMethod.DELETE) {
      builder.contentType(MediaType.APPLICATION_JSON).content("{}");
    }
    return mvc.perform(builder).andReturn().getResponse().getStatus();
  }

  @Test
  void theSpecCoversEveryAdminEndpointTheTestWillExercise() throws IOException {
    List<Endpoint> all = endpoints();

    assertThat(all).hasSizeGreaterThanOrEqualTo(25);
    assertThat(all.stream().filter(Endpoint::tenantScoped).count()).isGreaterThanOrEqualTo(24);
    assertThat(all.stream().filter(e -> e.path().contains("{")))
        .as("todos os parâmetros preenchidos")
        .isEmpty();
  }

  @Test
  void someoneWhoOnlyBelongsToTenantBNeverReachesTenantAOnAnyEndpoint() throws Exception {
    String betaOwner = oidc.userToken("dono-da-beta");
    List<String> leaked = new ArrayList<>();
    int checked = 0;
    for (Endpoint endpoint : endpoints()) {
      if (!endpoint.tenantScoped()) {
        continue;
      }
      checked++;
      int status = status(endpoint, betaOwner);
      if (status != 403) {
        leaked.add(endpoint.method() + " " + endpoint.path() + " -> " + status);
      }
    }

    assertThat(checked).isGreaterThanOrEqualTo(24);
    assertThat(leaked).as("endpoints que não responderam 403 ao tenant errado").isEmpty();
  }

  @Test
  void theSameUserDoesPassOnHisOwnTenantSoTheDenialAboveIsReal() throws Exception {
    String betaOwner = oidc.userToken("dono-da-beta");

    var own =
        mvc.perform(
                request(HttpMethod.GET, "/api/v1/tenants/beta/integrations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + betaOwner))
            .andReturn()
            .getResponse();

    assertThat(own.getStatus()).isEqualTo(200);
  }

  @Test
  void anonymousCallersAndSystemClientsNeverReachUserRoutes() throws Exception {
    var token =
        mvc.perform(
                request(HttpMethod.POST, "/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "client_credentials")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(
                                    "beta-hml:segredo-beta".getBytes(StandardCharsets.UTF_8))))
            .andReturn()
            .getResponse();
    assertThat(token.getStatus()).isEqualTo(200);
    String client =
        (String)
            com.nimbusds.jose.util.JSONObjectUtils.parse(token.getContentAsString())
                .get("access_token");
    List<String> problems = new ArrayList<>();
    for (Endpoint endpoint : endpoints()) {
      int anonymous = status(endpoint, null);
      int system = status(endpoint, client);
      if (anonymous != 401) {
        problems.add("anônimo " + endpoint.method() + " " + endpoint.path() + " -> " + anonymous);
      }
      if (system != 403) {
        problems.add("sistema " + endpoint.method() + " " + endpoint.path() + " -> " + system);
      }
    }

    assertThat(problems).isEmpty();
  }

  @Test
  void aSystemClientOfTenantBNeverReachesTenantAExecutionRoutes() throws Exception {
    var token =
        mvc.perform(
                request(HttpMethod.POST, "/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("grant_type", "client_credentials")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Basic "
                            + Base64.getEncoder()
                                .encodeToString(
                                    "beta-hml:segredo-beta".getBytes(StandardCharsets.UTF_8))))
            .andReturn()
            .getResponse();
    String client =
        (String)
            com.nimbusds.jose.util.JSONObjectUtils.parse(token.getContentAsString())
                .get("access_token");

    for (String route : List.of("/acme/api/pedidos", "/acme/api/qualquer/coisa", "/acme/api/")) {
      for (HttpMethod method :
          List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
        var response =
            mvc.perform(
                    request(method, route)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + client)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(method + " " + route).isEqualTo(404);
      }
    }
  }
}
