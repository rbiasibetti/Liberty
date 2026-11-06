package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaTenantConnectionResolver.class)
class JpaTenantConnectionResolverTest {

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

  @Autowired TenantRepository tenants;
  @Autowired TenantConnectionRepository connections;
  @Autowired JpaTenantConnectionResolver resolver;

  private static int counter;

  private TenantScope seed(String tenantStatus, String connectionStatus) {
    String slug = "res" + (++counter) + "-t";
    TenantEntity tenant = new TenantEntity(slug, "T", "STANDARD");
    tenant.setStatus(tenantStatus);
    tenants.save(tenant);
    TenantConnectionEntity connection =
        new TenantConnectionEntity(
            tenant.getId(),
            EnvironmentKind.HML,
            "jdbc:sqlserver://h;encrypt=true",
            "app",
            "liberty/x",
            7);
    connection.setStatus(connectionStatus);
    connections.save(connection);
    return new TenantScope(new TenantId(slug), EnvironmentKind.HML);
  }

  @Test
  void resolvesActiveTenantsAndConnections() {
    TenantScope scope = seed("ACTIVE", "ACTIVE");

    var info = resolver.resolve(scope).orElseThrow();

    assertThat(info.jdbcUrl()).isEqualTo("jdbc:sqlserver://h;encrypt=true");
    assertThat(info.username()).isEqualTo("app");
    assertThat(info.secretRef()).isEqualTo("liberty/x");
    assertThat(info.poolMax()).isEqualTo(7);
  }

  @Test
  void ignoresSuspendedTenantsDisabledConnectionsAndOtherEnvironments() {
    assertThat(resolver.resolve(seed("SUSPENDED", "ACTIVE"))).isEmpty();
    assertThat(resolver.resolve(seed("ACTIVE", "DISABLED"))).isEmpty();
    TenantScope scope = seed("ACTIVE", "ACTIVE");
    assertThat(resolver.resolve(new TenantScope(scope.tenant(), EnvironmentKind.PRD))).isEmpty();
    assertThat(resolver.resolve(new TenantScope(new TenantId("nao-existe"), EnvironmentKind.HML)))
        .isEmpty();
  }
}
