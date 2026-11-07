package br.com.liberty.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaApiClientDirectoryTest {

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

  @Autowired ApiClientRepository clients;
  @Autowired TenantRepository tenants;
  @Autowired PlatformTransactionManager transactionManager;

  private static int counter;

  private long tenant(String status) {
    TenantEntity tenant = new TenantEntity("dir" + (++counter) + "-t", "T", "STANDARD");
    tenant.setStatus(status);
    return tenants.save(tenant).getId();
  }

  private JpaApiClientDirectory directory(AtomicLong clock) {
    var readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    Ticker ticker = clock::get;
    return new JpaApiClientDirectory(
        clients, tenants, readOnly, Clock.systemUTC(), Duration.ofSeconds(15), ticker);
  }

  private ApiClientEntity client(long tenantId, String clientId, String scopes) {
    return clients.save(
        new ApiClientEntity(tenantId, clientId, "{bcrypt}hash", EnvironmentKind.HML, scopes));
  }

  @Test
  void describesActiveClientsWithTenantSlugAndScopes() {
    long tenantId = tenant("ACTIVE");
    client(tenantId, "dir-ativo", "  integration:execute:a   integration:execute:b ");

    var found = directory(new AtomicLong()).findFresh("dir-ativo").orElseThrow();

    assertThat(found.usable()).isTrue();
    assertThat(found.tenantSlug()).startsWith("dir");
    assertThat(found.environment()).isEqualTo(EnvironmentKind.HML);
    assertThat(found.scopes())
        .containsExactlyInAnyOrder("integration:execute:a", "integration:execute:b");
    assertThat(found.toString()).doesNotContain("hash");
  }

  @Test
  void revokedExpiredAndSuspendedTenantClientsAreNotUsable() {
    long active = tenant("ACTIVE");
    ApiClientEntity revoked = client(active, "dir-revogado", "s");
    revoked.setStatus("REVOKED");
    clients.save(revoked);
    ApiClientEntity revokedAt = client(active, "dir-revogado-em", "s");
    revokedAt.setRevokedAt(Instant.now());
    clients.save(revokedAt);
    ApiClientEntity expired = client(active, "dir-expirado", "s");
    expired.setExpiresAt(Instant.now().minusSeconds(1));
    clients.save(expired);
    client(tenant("SUSPENDED"), "dir-suspenso", "s");
    var directory = directory(new AtomicLong());

    for (String id :
        new String[] {"dir-revogado", "dir-revogado-em", "dir-expirado", "dir-suspenso"}) {
      assertThat(directory.findFresh(id).orElseThrow().usable()).as(id).isFalse();
    }
    assertThat(directory.findFresh("dir-inexistente")).isEmpty();
  }

  @Test
  void cachedReadsLagBehindUntilTheTtlButFreshReadsNever() {
    long tenantId = tenant("ACTIVE");
    ApiClientEntity entity = client(tenantId, "dir-cache", "s");
    var clock = new AtomicLong();
    var directory = directory(clock);
    assertThat(directory.findCached("dir-cache").orElseThrow().usable()).isTrue();

    entity.setStatus("REVOKED");
    clients.save(entity);

    assertThat(directory.findCached("dir-cache").orElseThrow().usable())
        .as("ainda no cache")
        .isTrue();
    assertThat(directory.findFresh("dir-cache").orElseThrow().usable())
        .as("leitura direta")
        .isFalse();
    assertThat(directory.findCached("dir-cache").orElseThrow().usable())
        .as("a direta atualiza o cache")
        .isFalse();

    entity.setStatus("ACTIVE");
    clients.save(entity);
    clock.addAndGet(Duration.ofSeconds(16).toNanos());
    assertThat(directory.findCached("dir-cache").orElseThrow().usable()).as("após o TTL").isTrue();
  }
}
