package br.com.liberty.engine.route;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.persistence.IntegrationEntity;
import br.com.liberty.infrastructure.persistence.IntegrationRepository;
import br.com.liberty.infrastructure.persistence.IntegrationVersionEntity;
import br.com.liberty.infrastructure.persistence.IntegrationVersionRepository;
import br.com.liberty.infrastructure.persistence.PublicationEntity;
import br.com.liberty.infrastructure.persistence.PublicationRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import br.com.liberty.snapshot.SnapshotService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaPublicationSource.class, JpaPublicationSourceTest.Config.class})
class JpaPublicationSourceTest {

  @org.springframework.boot.test.context.TestConfiguration
  static class Config {
    @org.springframework.context.annotation.Bean
    SnapshotService snapshotService() {
      return new SnapshotService();
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    var db = CoreTestDatabase.create();
    registry.add("spring.datasource.url", db::url);
    registry.add("spring.datasource.username", db::user);
    registry.add("spring.datasource.password", db::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
  }

  @Autowired JpaPublicationSource source;
  @Autowired TenantRepository tenants;
  @Autowired IntegrationRepository integrations;
  @Autowired IntegrationVersionRepository versions;
  @Autowired PublicationRepository publications;
  @Autowired EntityManager em;

  private static int counter;

  private static String snapshot(String key, String method, String route) {
    return "{\"schemaVersion\":1,\"integration\":{\"key\":\""
        + key
        + "\",\"name\":\"N\",\"method\":\""
        + method
        + "\",\"route\":\""
        + route
        + "\"},"
        + "\"request\":{},\"operation\":{\"type\":\"SELECT\",\"select\":{\"table\":\"dbo.t\",\"columns\":[\"id\"]}}}";
  }

  private TenantEntity tenant() {
    return tenants.save(new TenantEntity("pub" + (++counter) + "-t", "T", "STANDARD"));
  }

  /** Cria integração + versão + publicação; devolve a publicação. */
  private PublicationEntity publish(
      TenantEntity tenant,
      String key,
      String route,
      EnvironmentKind env,
      boolean active,
      String snapshotJson) {
    IntegrationEntity integration =
        integrations.save(new IntegrationEntity(tenant.getId(), key, "N", "GET", route));
    IntegrationVersionEntity version =
        versions.save(
            new IntegrationVersionEntity(
                tenant.getId(),
                integration.getId(),
                1,
                VersionState.PUBLISHED,
                snapshotJson,
                new byte[32],
                "ana"));
    return publications.save(
        new PublicationEntity(
            tenant.getId(), integration.getId(), version.getId(), env, active, "bia"));
  }

  private static TenantScope scope(TenantEntity tenant, EnvironmentKind env) {
    return new TenantScope(new TenantId(tenant.getSlug()), env);
  }

  private static boolean serves(PublicationSource.Loaded loaded, String segment) {
    return loaded.table().resolve("GET", List.of(segment)).match().isPresent();
  }

  @Test
  void servesOnlyActivePublicationsOfTheTenantAndEnvironment() {
    TenantEntity acme = tenant();
    TenantEntity other = tenant();
    publish(acme, "ativa", "/ativa", EnvironmentKind.HML, true, snapshot("ativa", "GET", "/ativa"));
    publish(
        acme,
        "inativa",
        "/inativa",
        EnvironmentKind.HML,
        false,
        snapshot("inativa", "GET", "/inativa"));
    publish(
        acme, "so-prd", "/so-prd", EnvironmentKind.PRD, true, snapshot("so-prd", "GET", "/so-prd"));
    publish(
        other,
        "de-outro",
        "/de-outro",
        EnvironmentKind.HML,
        true,
        snapshot("de-outro", "GET", "/de-outro"));
    em.flush();

    var hml = source.load(scope(acme, EnvironmentKind.HML));
    var prd = source.load(scope(acme, EnvironmentKind.PRD));

    assertThat(serves(hml, "ativa")).isTrue();
    assertThat(serves(hml, "inativa")).isFalse();
    assertThat(serves(hml, "so-prd")).isFalse();
    assertThat(serves(hml, "de-outro")).isFalse();
    assertThat(serves(prd, "so-prd")).isTrue();
    assertThat(serves(prd, "ativa")).isFalse();
    assertThat(hml.table().size()).isEqualTo(1);
  }

  @Test
  void archivedIntegrationsAreNotServed() {
    TenantEntity acme = tenant();
    PublicationEntity p =
        publish(
            acme, "velha", "/velha", EnvironmentKind.HML, true, snapshot("velha", "GET", "/velha"));
    IntegrationEntity integration =
        integrations.findByIdAndTenantId(p.getIntegrationId(), acme.getId()).orElseThrow();
    integration.setArchived(true);
    integrations.save(integration);
    em.flush();

    assertThat(serves(source.load(scope(acme, EnvironmentKind.HML)), "velha")).isFalse();
  }

  @Test
  void aSnapshotThatIsInvalidOrDisagreesWithTheRegistrationIsSkippedNotServed() {
    TenantEntity acme = tenant();
    publish(acme, "boa", "/boa", EnvironmentKind.HML, true, snapshot("boa", "GET", "/boa"));
    publish(acme, "quebrada", "/quebrada", EnvironmentKind.HML, true, "{\"schemaVersion\":1}");
    publish(
        acme,
        "rota-trocada",
        "/rota-trocada",
        EnvironmentKind.HML,
        true,
        snapshot("rota-trocada", "GET", "/outra-rota"));
    publish(
        acme,
        "chave-trocada",
        "/chave-trocada",
        EnvironmentKind.HML,
        true,
        snapshot("outra-chave", "GET", "/chave-trocada"));
    publish(
        acme,
        "metodo-trocado",
        "/metodo-trocado",
        EnvironmentKind.HML,
        true,
        snapshot("metodo-trocado", "POST", "/metodo-trocado"));
    em.flush();

    var loaded = source.load(scope(acme, EnvironmentKind.HML));

    assertThat(serves(loaded, "boa")).isTrue();
    assertThat(loaded.table().size()).isEqualTo(1);
  }

  @Test
  void theTokenChangesWhenSomethingIsPublishedOrUnpublished() {
    TenantEntity acme = tenant();
    PublicationEntity first =
        publish(acme, "a", "/a", EnvironmentKind.HML, true, snapshot("a", "GET", "/a"));
    em.flush();
    String t1 = source.token(scope(acme, EnvironmentKind.HML));
    assertThat(source.load(scope(acme, EnvironmentKind.HML)).token()).isEqualTo(t1);
    assertThat(source.token(scope(acme, EnvironmentKind.HML)))
        .as("estável sem mudança")
        .isEqualTo(t1);

    publish(acme, "b", "/b", EnvironmentKind.HML, true, snapshot("b", "GET", "/b"));
    em.flush();
    String t2 = source.token(scope(acme, EnvironmentKind.HML));

    first.setActive(false);
    publications.save(first);
    em.flush();
    String t3 = source.token(scope(acme, EnvironmentKind.HML));

    assertThat(List.of(t1, t2, t3)).doesNotHaveDuplicates();
    assertThat(source.token(scope(acme, EnvironmentKind.PRD))).isNotEqualTo(t3);
  }

  @Test
  void unknownOrSuspendedTenantsServeNothing() {
    TenantEntity suspended = tenant();
    suspended.setStatus("SUSPENDED");
    tenants.save(suspended);
    publish(suspended, "x", "/x", EnvironmentKind.HML, true, snapshot("x", "GET", "/x"));
    em.flush();

    assertThat(source.load(scope(suspended, EnvironmentKind.HML)).table().size()).isZero();
    assertThat(source.token(scope(suspended, EnvironmentKind.HML))).isEqualTo("none");
    assertThat(
            source
                .load(new TenantScope(new TenantId("nao-existe"), EnvironmentKind.HML))
                .table()
                .size())
        .isZero();
  }
}
