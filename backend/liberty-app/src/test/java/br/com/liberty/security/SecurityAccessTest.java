package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.security.SigningKeyRing;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Autenticação e autorização de ponta a ponta: token real do servidor, validadores, papéis e
 * isolamento.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityAccessTest {

  private static final String ISSUER = "http://liberty.test";
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
  @Autowired ApiClientRepository clients;
  @Autowired UserRoleRepository roles;
  @Autowired PasswordEncoder encoder;
  @Autowired SigningKeyRing ring;

  private static boolean seeded;

  @BeforeEach
  void seed() {
    if (seeded) {
      return;
    }
    seeded = true;
    long acme = tenants.save(new TenantEntity("acme", "Acme", "STANDARD")).getId();
    long beta = tenants.save(new TenantEntity("beta", "Beta", "STANDARD")).getId();
    TenantEntity gone = new TenantEntity("gone", "Gone", "STANDARD");
    gone.setStatus("SUSPENDED");
    long goneId = tenants.save(gone).getId();

    String scopes = "integration:execute:pedido integration:execute:cliente";
    client(acme, "acme-hml", EnvironmentKind.HML, scopes);
    client(acme, "acme-prd", EnvironmentKind.PRD, scopes);
    client(acme, "acme-revogavel", EnvironmentKind.HML, scopes);
    client(beta, "beta-hml", EnvironmentKind.HML, "integration:execute:pedido");
    client(goneId, "gone-hml", EnvironmentKind.HML, scopes);
    ApiClientEntity revoked = client(acme, "acme-revogado", EnvironmentKind.HML, scopes);
    revoked.setStatus("REVOKED");
    revoked.setRevokedAt(Instant.now());
    clients.save(revoked);
    ApiClientEntity expired = client(acme, "acme-expirado", EnvironmentKind.HML, scopes);
    expired.setExpiresAt(Instant.now().minusSeconds(60));
    clients.save(expired);

    roles.save(new UserRoleEntity("viewer", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("integrator", acme, TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("publisher", acme, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("multi", acme, TenantRole.VIEWER));
    roles.save(new UserRoleEntity("multi", beta, TenantRole.PUBLISHER));
    roles.save(new UserRoleEntity("on-gone", goneId, TenantRole.PUBLISHER));
  }

  private ApiClientEntity client(
      long tenantId, String clientId, EnvironmentKind env, String scopes) {
    return clients.save(
        new ApiClientEntity(
            tenantId, clientId, encoder.encode("segredo-" + clientId), env, scopes));
  }

  // ---------- helpers ----------

  private MockHttpServletResponse call(String method, String path, String bearer) throws Exception {
    var request = method.equals("OPTIONS") ? options(path) : get(path);
    if (bearer != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private int status(String path, String bearer) throws Exception {
    return call("GET", path, bearer).getStatus();
  }

  private MockHttpServletResponse tokenRequest(String clientId, String secret, String grantType)
      throws Exception {
    var request =
        post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", grantType);
    if (clientId != null) {
      request.header(
          HttpHeaders.AUTHORIZATION,
          "Basic "
              + java.util.Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes()));
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private String clientToken(String clientId) throws Exception {
    MockHttpServletResponse response =
        tokenRequest(clientId, "segredo-" + clientId, "client_credentials");
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return (String) JSONObjectUtils.parse(response.getContentAsString()).get("access_token");
  }

  /**
   * Token assinado com a chave real do servidor, com claims ajustáveis, para exercitar os
   * validadores.
   */
  private String forged(Map<String, Object> overrides) {
    Map<String, Object> claims =
        new HashMap<>(
            Map.of(
                "client_id",
                "acme-hml",
                "tenant_id",
                "acme",
                "env",
                "HML",
                "scope",
                List.of("integration:execute:pedido"),
                "aud",
                List.of("liberty-api")));
    claims.putAll(overrides);
    JWTClaimsSet.Builder builder =
        new JWTClaimsSet.Builder()
            .subject("acme-hml")
            .issuer(ISSUER)
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    claims.forEach(builder::claim);
    if (claims.containsKey("iss")) {
      builder.issuer((String) claims.get("iss"));
    }
    if (claims.containsKey("exp")) {
      builder.expirationTime((Date) claims.get("exp"));
    }
    RSAKey key = (RSAKey) ring.signingJwkSet().getKeys().getFirst();
    return OidcStub.sign(key, builder.build(), key.getKeyID());
  }

  // ---------- emissão de tokens ----------

  @Test
  void issuesAVerifiableShortLivedTokenWithTenantEnvironmentAndScopes() throws Exception {
    MockHttpServletResponse response =
        tokenRequest("acme-hml", "segredo-acme-hml", "client_credentials");

    assertThat(response.getStatus()).isEqualTo(200);
    Map<String, Object> body = JSONObjectUtils.parse(response.getContentAsString());
    assertThat(body).containsEntry("token_type", "Bearer");
    assertThat(((Number) body.get("expires_in")).longValue()).isBetween(890L, 900L);
    SignedJWT jwt = SignedJWT.parse((String) body.get("access_token"));
    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo(ring.current().kid());
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getAudience()).containsExactly("liberty-api");
    assertThat(claims.getStringClaim("tenant_id")).isEqualTo("acme");
    assertThat(claims.getStringClaim("env")).isEqualTo("HML");
    assertThat(claims.getStringClaim("client_id")).isEqualTo("acme-hml");
    assertThat(claims.getStringListClaim("scope"))
        .containsExactlyInAnyOrder("integration:execute:pedido", "integration:execute:cliente");
    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
        .isEqualTo(900_000L);

    JWKSet jwks =
        JWKSet.parse(
            mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString());
    assertThat(
            jwt.verify(new RSASSAVerifier((RSAKey) jwks.getKeyByKeyId(jwt.getHeader().getKeyID()))))
        .isTrue();
  }

  @Test
  void jwksPublishesOnlyPublicKeyMaterialAndMetadataIsPublic() throws Exception {
    String jwks = mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();

    Map<String, Object> key =
        ((List<Map<String, Object>>) JSONObjectUtils.parse(jwks).get("keys")).getFirst();
    assertThat(key)
        .containsKeys("kty", "n", "e", "kid")
        .doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    assertThat(
            mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void rejectsBadCredentialsUnknownRevokedExpiredAndSuspendedClients() throws Exception {
    assertThat(tokenRequest("acme-hml", "errada", "client_credentials").getStatus()).isEqualTo(401);
    assertThat(tokenRequest("ninguem", "x", "client_credentials").getStatus()).isEqualTo(401);
    assertThat(
            tokenRequest("acme-revogado", "segredo-acme-revogado", "client_credentials")
                .getStatus())
        .isEqualTo(401);
    assertThat(
            tokenRequest("acme-expirado", "segredo-acme-expirado", "client_credentials")
                .getStatus())
        .isEqualTo(401);
    assertThat(tokenRequest("gone-hml", "segredo-gone-hml", "client_credentials").getStatus())
        .isEqualTo(401);
    assertThat(tokenRequest(null, null, "client_credentials").getStatus()).isEqualTo(401);
  }

  @Test
  void secretInTheBodyAndOtherGrantsAreNotAccepted() throws Exception {
    var post =
        post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "client_credentials")
            .param("client_id", "acme-hml")
            .param("client_secret", "segredo-acme-hml");
    assertThat(mvc.perform(post).andReturn().getResponse().getStatus()).isEqualTo(401);

    for (String grant :
        new String[] {
          "password",
          "authorization_code",
          "refresh_token",
          "urn:ietf:params:oauth:grant-type:device_code"
        }) {
      MockHttpServletResponse response = tokenRequest("acme-hml", "segredo-acme-hml", grant);
      assertThat(response.getStatus()).as(grant).isBetween(400, 401);
      assertThat(response.getContentAsString()).doesNotContain("access_token");
    }
    assertThat(mvc.perform(get("/oauth2/token")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }

  @Test
  void otherAuthorizationServerEndpointsAreNotExposed() throws Exception {
    for (String path :
        new String[] {
          "/oauth2/authorize",
          "/oauth2/introspect",
          "/oauth2/revoke",
          "/userinfo",
          "/connect/register"
        }) {
      assertThat(status(path, null)).as(path).isEqualTo(401);
    }
  }

  // ---------- execução: tenant vem do token ----------

  @Test
  void clientsReachOnlyTheirOwnTenantAndEnvironment() throws Exception {
    String acmeHml = clientToken("acme-hml");
    String acmePrd = clientToken("acme-prd");

    MockHttpServletResponse own = call("GET", "/acme/api/echo", acmeHml);
    assertThat(own.getStatus()).isEqualTo(200);
    assertThat(own.getContentAsString()).isEqualTo("acme/HML");
    assertThat(call("GET", "/acme/api/echo", acmePrd).getContentAsString()).isEqualTo("acme/PRD");
    assertThat(status("/beta/api/echo", acmeHml)).isEqualTo(404);
    assertThat(status("/gone/api/echo", acmeHml)).isEqualTo(404);
    assertThat(status("/inexistente/api/echo", acmeHml)).isEqualTo(404);
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void executionRoutesRequireAValidClientToken() throws Exception {
    assertThat(status("/acme/api/echo", null)).isEqualTo(401);
    assertThat(status("/acme/api/echo", "lixo")).isEqualTo(401);
    assertThat(call("GET", "/acme/api/echo", null).getHeader("WWW-Authenticate"))
        .startsWith("Bearer");
    assertThat(call("GET", "/acme/api/echo", null).getContentType())
        .startsWith("application/problem+json");
  }

  @Test
  void userTokensCannotCallExecutionRoutesAndClientTokensCannotCallUserRoutes() throws Exception {
    assertThat(status("/acme/api/echo", oidc.userToken("publisher"))).isEqualTo(403);
    assertThat(status("/api/v1/me", clientToken("acme-hml"))).isEqualTo(403);
    assertThat(status("/api/v1/tenants/acme/probe/viewer", clientToken("acme-hml"))).isEqualTo(403);
  }

  @Test
  void anOidcTokenWithAClientIdClaimNeverBecomesAClient() throws Exception {
    String token =
        oidc.userToken(
            "publisher", Map.of("client_id", "acme-hml", "tenant_id", "acme", "env", "HML"));

    assertThat(status("/acme/api/echo", token)).isEqualTo(403);
  }

  // ---------- validadores dos tokens de cliente ----------

  @Test
  void forgedAndInconsistentClientTokensAreRejected() throws Exception {
    assertThat(status("/acme/api/echo", forged(Map.of())))
        .as("controle: o forjado válido passa")
        .isEqualTo(200);
    assertThat(status("/acme/api/echo", forged(Map.of("aud", List.of("outra-api")))))
        .as("audiência")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("iss", "http://evil.test"))))
        .as("emissor")
        .isEqualTo(401);
    assertThat(
            status(
                "/acme/api/echo",
                forged(Map.of("exp", Date.from(Instant.now().minusSeconds(120))))))
        .as("expirado")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("env", "PRD"))))
        .as("ambiente diferente do cliente")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("tenant_id", "beta"))))
        .as("tenant diferente do cliente")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("client_id", "nao-existe"))))
        .as("cliente desconhecido")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("client_id", "acme-revogado"))))
        .as("cliente revogado")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", forged(Map.of("client_id", "acme-expirado"))))
        .as("cliente expirado")
        .isEqualTo(401);
    assertThat(
            status("/gone/api/echo", forged(Map.of("client_id", "gone-hml", "tenant_id", "gone"))))
        .as("tenant suspenso")
        .isEqualTo(401);
  }

  @Test
  void tokensNotSignedByTheServerAreRejected() throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("acme-hml")
            .issuer(ISSUER)
            .audience("liberty-api")
            .claim("client_id", "acme-hml")
            .claim("tenant_id", "acme")
            .claim("env", "HML")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
    RSAKey attacker = new RSAKeyGenerator(2048).keyID(ring.current().kid()).generate();

    assertThat(status("/acme/api/echo", OidcStub.sign(attacker, claims, ring.current().kid())))
        .as("chave do atacante com o mesmo kid")
        .isEqualTo(401);
    assertThat(status("/acme/api/echo", new PlainJWT(claims).serialize()))
        .as("alg none")
        .isEqualTo(401);
    String valid = forged(Map.of());
    String[] parts = valid.split("\\.");
    String tampered =
        parts[0]
            + "."
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                        .replace("\"acme\"", "\"beta\"")
                        .getBytes())
            + "."
            + parts[2];
    assertThat(status("/beta/api/echo", tampered)).as("payload adulterado").isEqualTo(401);
  }

  @Test
  void revokingAClientStopsItsTokensWithinTheCacheTtl() throws Exception {
    String token = clientToken("acme-revogavel");
    assertThat(status("/acme/api/echo", token)).isEqualTo(200);

    ApiClientEntity client = clients.findByClientId("acme-revogavel").orElseThrow();
    client.setStatus("REVOKED");
    client.setRevokedAt(Instant.now());
    clients.save(client);
    Thread.sleep(400);

    assertThat(status("/acme/api/echo", token)).isEqualTo(401);
    assertThat(
            tokenRequest("acme-revogavel", "segredo-acme-revogavel", "client_credentials")
                .getStatus())
        .isEqualTo(401);
  }

  // ---------- usuários (OIDC) ----------

  @Test
  void userTokensFromUntrustedOrWrongSourcesAreRejected() throws Exception {
    assertThat(status("/api/v1/me", oidc.userToken("viewer"))).as("controle").isEqualTo(200);
    assertThat(status("/api/v1/me", oidc.userToken("viewer", Map.of("azp", "outra-app"))))
        .as("azp")
        .isEqualTo(401);
    assertThat(status("/api/v1/me", oidc.userToken("viewer", Map.of("iss", "http://evil.test"))))
        .as("emissor")
        .isEqualTo(401);
    assertThat(
            status(
                "/api/v1/me",
                oidc.userToken(
                    "viewer", Map.of("exp", Date.from(Instant.now().minusSeconds(120))))))
        .as("expirado")
        .isEqualTo(401);
    RSAKey attacker = new RSAKeyGenerator(2048).keyID("oidc-key").generate();
    String forgedByAttacker =
        OidcStub.sign(
            attacker,
            OidcStub.baseClaims("viewer", oidc.issuer, "liberty-frontend", Map.of()),
            "oidc-key");
    assertThat(status("/api/v1/me", forgedByAttacker))
        .as("assinatura de outra chave")
        .isEqualTo(401);
    assertThat(status("/api/v1/me", forged(Map.of("iss", oidc.issuer))))
        .as("chave do servidor com o emissor do OIDC")
        .isEqualTo(401);
  }

  @Test
  void meListsTheTenantsAndEffectiveRoles() throws Exception {
    String body = call("GET", "/api/v1/me", oidc.userToken("multi")).getContentAsString();
    Map<String, Object> me = JSONObjectUtils.parse(body);

    assertThat(me).containsEntry("sub", "multi").containsEntry("platformAdmin", false);
    assertThat((Map<String, Object>) me.get("tenants")).containsOnlyKeys("acme", "beta");
    assertThat(
            ((Map<String, Object>)
                JSONObjectUtils.parse(
                    call("GET", "/api/v1/me", oidc.adminToken("root")).getContentAsString())))
        .containsEntry("platformAdmin", true);
    Map<String, Object> suspended =
        JSONObjectUtils.parse(
            call("GET", "/api/v1/me", oidc.userToken("on-gone")).getContentAsString());
    assertThat((Map<String, Object>) suspended.get("tenants")).isEmpty();
    assertThat(call("GET", "/api/v1/me", oidc.userToken("viewer")).getHeader("Cache-Control"))
        .contains("no-store");
  }

  /** Matriz de papéis: quem pode o quê, por tenant, incluindo o cruzamento entre tenants. */
  @Test
  void roleMatrix() throws Exception {
    record Case(String user, String path, int expected) {}
    List<Case> cases = new ArrayList<>();
    String[][] grants = {
      // usuário, viewer, integrator, publisher (no tenant acme)
      {"viewer", "200", "403", "403"},
      {"integrator", "200", "200", "403"},
      {"publisher", "200", "200", "200"},
      {"multi", "200", "403", "403"},
      {"ninguem", "403", "403", "403"},
    };
    for (String[] grant : grants) {
      cases.add(
          new Case(grant[0], "/api/v1/tenants/acme/probe/viewer", Integer.parseInt(grant[1])));
      cases.add(
          new Case(grant[0], "/api/v1/tenants/acme/probe/integrator", Integer.parseInt(grant[2])));
      cases.add(
          new Case(grant[0], "/api/v1/tenants/acme/probe/publisher", Integer.parseInt(grant[3])));
    }
    cases.add(new Case("multi", "/api/v1/tenants/beta/probe/publisher", 200));
    cases.add(
        new Case(
            "viewer", "/api/v1/tenants/beta/probe/viewer", 403)); // papel em outro tenant não vale
    cases.add(new Case("publisher", "/api/v1/tenants/beta/probe/viewer", 403));
    cases.add(new Case("on-gone", "/api/v1/tenants/gone/probe/viewer", 403)); // tenant suspenso
    cases.add(
        new Case(
            "publisher",
            "/api/v1/tenants/inexistente/probe/viewer",
            403)); // mesma resposta de não-membro
    cases.add(new Case("publisher", "/api/v1/platform/probe", 403));
    List<String> failures = new ArrayList<>();
    for (Case c : cases) {
      int actual = status(c.path(), oidc.userToken(c.user()));
      if (actual != c.expected()) {
        failures.add(c.user() + " " + c.path() + " esperado " + c.expected() + " veio " + actual);
      }
    }
    assertThat(failures).isEmpty();
  }

  @Test
  void platformAdminIsNotATenantMemberAndOnlyHeReachesPlatformRoutes() throws Exception {
    String admin = oidc.adminToken("root");

    assertThat(status("/api/v1/platform/probe", admin)).isEqualTo(200);
    assertThat(status("/api/v1/tenants/acme/probe/viewer", admin)).isEqualTo(403);
    assertThat(status("/acme/api/echo", admin)).isEqualTo(403);
  }

  // ---------- postura geral ----------

  @Test
  void everythingNotExplicitlyAllowedIsDenied() throws Exception {
    assertThat(status("/qualquer-coisa", null)).isEqualTo(401);
    assertThat(status("/qualquer-coisa", oidc.userToken("publisher"))).isEqualTo(403);
    assertThat(status("/actuator/env", null)).isEqualTo(401);
    assertThat(status("/actuator/env", oidc.adminToken("root"))).isEqualTo(403);
    assertThat(status("/actuator/health", null)).isEqualTo(200);
    assertThat(status("/actuator/health/readiness", null)).isEqualTo(200);
  }

  @Test
  void corsAllowsOnlyConfiguredOriginsAndNeverCredentials() throws Exception {
    var allowed =
        options("/api/v1/me")
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "authorization");
    var denied =
        options("/api/v1/me")
            .header("Origin", "http://evil.test")
            .header("Access-Control-Request-Method", "GET");

    MockHttpServletResponse ok = mvc.perform(allowed).andReturn().getResponse();
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isNull();
    assertThat(mvc.perform(denied).andReturn().getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void securityHeadersAreSetOnApiResponses() throws Exception {
    MockHttpServletResponse response = call("GET", "/api/v1/me", oidc.userToken("viewer"));

    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'none'");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getCookies()).isEmpty();
  }
}
