package br.com.liberty.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Mapeamento JPA contra o schema real: o Hibernate valida as colunas na subida (ddl-auto=validate).
 */
@DataJpaTest
@Import(PersistenceConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceMappingTest {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    var db = CoreTestDatabase.create();
    registry.add("spring.datasource.url", db::url);
    registry.add("spring.datasource.username", db::user);
    registry.add("spring.datasource.password", db::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
  }

  @Autowired TenantRepository tenants;
  @Autowired TenantConnectionRepository connections;
  @Autowired UserRoleRepository roles;
  @Autowired ApiClientRepository clients;
  @Autowired IntegrationRepository integrations;
  @Autowired IntegrationVersionRepository versions;
  @Autowired PublicationRepository publications;
  @Autowired IdempotencyRepository idempotency;
  @Autowired AuditExecutionRepository audit;
  @Autowired ExportJobRepository exports;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager em;

  private static int counter;

  private TenantEntity tenant() {
    return tenants.save(new TenantEntity("m" + (++counter) + "-t", "Tenant", "STANDARD"));
  }

  @Test
  void tenantRoundTripKeepsUtcInstants() {
    TenantEntity saved = tenant();

    TenantEntity found = tenants.findBySlug(saved.getSlug()).orElseThrow();

    assertThat(found.getName()).isEqualTo("Tenant");
    assertThat(found.getStatus()).isEqualTo("ACTIVE");
    assertThat(found.getPlanCode()).isEqualTo("STANDARD");
    assertThat(found.getCreatedAt())
        .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
  }

  @Test
  void connectionsClientsAndRolesRoundTrip() {
    TenantEntity t = tenant();
    connections.save(
        new TenantConnectionEntity(
            t.getId(), EnvironmentKind.HML, "jdbc:sqlserver://h", "u", "secret/a", 5));
    clients.save(
        new ApiClientEntity(
            t.getId(),
            "client-" + t.getId(),
            "$2a$hash",
            EnvironmentKind.PRD,
            "integration:execute:pedido"));
    roles.save(new UserRoleEntity("sub-1", t.getId(), TenantRole.INTEGRATOR));
    roles.save(new UserRoleEntity("sub-1", t.getId(), TenantRole.VIEWER));

    assertThat(connections.findByTenantIdAndEnvironment(t.getId(), EnvironmentKind.HML))
        .isPresent();
    assertThat(connections.findByTenantIdAndEnvironment(t.getId(), EnvironmentKind.PRD)).isEmpty();
    assertThat(clients.findByClientId("client-" + t.getId()).orElseThrow().getEnvironment())
        .isEqualTo(EnvironmentKind.PRD);
    assertThat(roles.findByIdUserSubAndIdTenantId("sub-1", t.getId()))
        .extracting(r -> r.getId().role())
        .containsExactlyInAnyOrder(TenantRole.INTEGRATOR, TenantRole.VIEWER);
  }

  @Test
  void integrationsAreFoundByKeyAndRouteWithinTheTenantOnly() {
    TenantEntity a = tenant();
    TenantEntity b = tenant();
    IntegrationEntity pedido =
        integrations.save(new IntegrationEntity(a.getId(), "pedido", "Pedido", "POST", "/pedidos"));
    IntegrationEntity velho = new IntegrationEntity(a.getId(), "velho", "Velho", "GET", "/velho");
    velho.setArchived(true);
    integrations.save(velho);

    assertThat(integrations.findByTenantIdAndIntegrationKey(a.getId(), "pedido")).isPresent();
    assertThat(integrations.findByTenantIdAndIntegrationKey(b.getId(), "pedido")).isEmpty();
    assertThat(integrations.findByIdAndTenantId(pedido.getId(), b.getId())).isEmpty();
    assertThat(
            integrations.findByTenantIdAndHttpMethodAndRoutePattern(a.getId(), "POST", "/pedidos"))
        .isPresent();
    assertThat(integrations.findByTenantIdAndArchivedFalseOrderByIntegrationKey(a.getId()))
        .extracting(IntegrationEntity::getIntegrationKey)
        .containsExactly("pedido");
  }

  @Test
  void versionsGetSequentialNumbersAndFollowTheStateMachine() {
    TenantEntity t = tenant();
    IntegrationEntity i =
        integrations.save(new IntegrationEntity(t.getId(), "v", "V", "POST", "/v"));
    assertThat(versions.lockAndGetMaxVersionNo(i.getId())).isZero();

    IntegrationVersionEntity v1 =
        versions.save(
            new IntegrationVersionEntity(
                t.getId(),
                i.getId(),
                versions.lockAndGetMaxVersionNo(i.getId()) + 1,
                VersionState.DRAFT,
                "{\"a\":1}",
                new byte[32],
                "ana"));
    em.flush();
    assertThat(versions.lockAndGetMaxVersionNo(i.getId())).isEqualTo(1);
    assertThat(
            versions.findByIntegrationIdAndTenantIdAndState(
                i.getId(), t.getId(), VersionState.DRAFT))
        .isPresent();
    assertThat(versions.findByIdAndTenantId(v1.getId(), tenant().getId())).isEmpty();

    v1.setState(VersionState.IN_REVIEW);
    versions.save(v1);
    em.flush(); // força o flush do UPDATE

    v1.setSnapshot("{\"a\":2}");
    assertThatThrownBy(
            () -> {
              versions.save(v1);
              em.flush();
            })
        .hasRootCauseMessage("O conteudo de uma versao fora do rascunho e imutavel");
  }

  @Test
  void activePublicationIsFoundPerEnvironment() {
    TenantEntity t = tenant();
    IntegrationEntity i =
        integrations.save(new IntegrationEntity(t.getId(), "pub", "P", "POST", "/pub"));
    IntegrationVersionEntity v =
        versions.save(
            new IntegrationVersionEntity(
                t.getId(), i.getId(), 1, VersionState.DRAFT, "{}", new byte[32], "ana"));
    publications.save(
        new PublicationEntity(t.getId(), i.getId(), v.getId(), EnvironmentKind.HML, true, "bia"));

    assertThat(
            publications.findByTenantIdAndIntegrationIdAndEnvironmentAndActiveTrue(
                t.getId(), i.getId(), EnvironmentKind.HML))
        .isPresent();
    assertThat(
            publications.findByTenantIdAndEnvironmentAndActiveTrue(t.getId(), EnvironmentKind.PRD))
        .isEmpty();
  }

  @Test
  void idempotencyEntriesCompleteAndExpire() {
    TenantEntity t = tenant();
    IntegrationEntity i =
        integrations.save(new IntegrationEntity(t.getId(), "idem", "I", "POST", "/idem"));
    var key = new IdempotencyEntity.Key(t.getId(), i.getId(), "k-1");
    IdempotencyEntity entry =
        idempotency.save(
            new IdempotencyEntity(key, new byte[32], Instant.now().minus(1, ChronoUnit.HOURS)));
    entry.complete((short) 201, "{\"ok\":true}");
    idempotency.save(entry);
    em.flush();

    IdempotencyEntity found = idempotency.findById(key).orElseThrow();
    assertThat(found.getStatus()).isEqualTo("DONE");
    assertThat(found.getHttpStatus()).isEqualTo((short) 201);
    assertThat(found.getResponse()).isEqualTo("{\"ok\":true}");
    assertThat(idempotency.deleteByExpiresAtBefore(Instant.now())).isEqualTo(1);
    assertThat(idempotency.findById(key)).isEmpty();
  }

  @Test
  void exportJobsCannotBeReadAcrossTenants() {
    TenantEntity a = tenant();
    TenantEntity b = tenant();
    ExportJobEntity job =
        exports.save(new ExportJobEntity(a.getId(), "ana", "PENDING", "CSV", "{}"));

    assertThat(exports.findByIdAndTenantId(job.getId(), a.getId())).isPresent();
    assertThat(exports.findByIdAndTenantId(job.getId(), b.getId())).isEmpty();
  }

  @Test
  void auditRowsAreReadPagedAndScopedByTenant() {
    TenantEntity a = tenant();
    TenantEntity b = tenant();
    for (int n = 0; n < 3; n++) {
      insertAudit(a.getId(), "req-a-" + n);
    }
    insertAudit(b.getId(), "req-b-0");

    var page =
        audit.findByTenantIdAndCreatedAtBetween(
            a.getId(),
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(1, ChronoUnit.DAYS),
            PageRequest.of(0, 2));

    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent())
        .hasSize(2)
        .allSatisfy(r -> assertThat(r.getTenantId()).isEqualTo(a.getId()));
    assertThat(audit.findByTenantIdAndRequestId(b.getId(), "req-a-0")).isEmpty();
    List<AuditExecutionEntity> byRequest = audit.findByTenantIdAndRequestId(b.getId(), "req-b-0");
    assertThat(byRequest).hasSize(1);
    assertThat(byRequest.get(0).getStatusCode()).isEqualTo((short) 201);
  }

  private void insertAudit(long tenantId, String requestId) {
    jdbc.update(
        "INSERT INTO int_audit_execution (tenant_id, integration_key, environment, http_method, route, status_code,"
            + " duration_ms, request_id) VALUES (?, 'pedido', 'PRD', 'POST', '/pedidos', 201, 12, ?)",
        tenantId,
        requestId);
  }
}
