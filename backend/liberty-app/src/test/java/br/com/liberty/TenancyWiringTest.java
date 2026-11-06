package br.com.liberty;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A fiação completa: core, cofre (falso, via HTTP), registro de tenants e JdbcTemplate de tenant.
 */
@SpringBootTest
class TenancyWiringTest {

  private static final CoreTestDatabase.Scratch TENANT_DB = CoreTestDatabase.create();
  private static HttpServer vault;

  static {
    try {
      vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      vault.createContext(
          "/v1/secret/data/liberty/tenants/fiacao/hml",
          exchange -> {
            boolean authorized =
                "teste".equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] body =
                ("{\"data\":{\"data\":{\"password\":\""
                        + TENANT_DB.password()
                        + "\"},\"metadata\":{}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(authorized ? 200 : 403, authorized ? body.length : -1);
            if (authorized) {
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
  }

  @AfterAll
  static void stopVault() {
    vault.stop(0);
  }

  @Autowired TenantRepository tenants;
  @Autowired TenantConnectionRepository connections;

  @Autowired
  @Qualifier("tenantJdbcTemplate")
  NamedParameterJdbcTemplate tenantJdbc;

  @Autowired Map<String, DataSource> dataSources;
  @Autowired ThreadPoolTaskExecutor applicationTaskExecutor;

  @Test
  void onlyTheCoreIsADataSourceBeanSoTheTenantRouterStaysOutOfHealthChecks() {
    assertThat(dataSources).containsOnlyKeys("coreDataSource");
  }

  @Test
  void aTenantRegisteredInTheCoreIsReachableThroughTheVaultAndTheTenantTemplate() throws Exception {
    try (Connection c =
            DriverManager.getConnection(TENANT_DB.url(), TENANT_DB.user(), TENANT_DB.password());
        Statement st = c.createStatement()) {
      st.execute("CREATE TABLE marker (nome VARCHAR(50))");
      st.execute("INSERT INTO marker VALUES ('banco-da-fiacao')");
    }
    TenantEntity tenant = tenants.save(new TenantEntity("fiacao", "Fiação", "STANDARD"));
    connections.save(
        new TenantConnectionEntity(
            tenant.getId(),
            EnvironmentKind.HML,
            TENANT_DB.url(),
            TENANT_DB.user(),
            "liberty/tenants/fiacao/hml",
            2));
    TenantScope scope = new TenantScope(new TenantId("fiacao"), EnvironmentKind.HML);

    String name =
        TenantContext.supplyAs(
            scope,
            () ->
                tenantJdbc
                    .getJdbcTemplate()
                    .queryForObject("SELECT nome FROM marker", String.class));

    assertThat(name).isEqualTo("banco-da-fiacao");
  }

  @Test
  void asyncTasksCarryTheTenant() throws Exception {
    TenantScope scope = new TenantScope(new TenantId("fiacao"), EnvironmentKind.PRD);

    var seen =
        TenantContext.supplyAs(scope, () -> applicationTaskExecutor.submit(TenantContext::current));

    assertThat(seen.get()).contains(scope);
    assertThat(TenantContext.current()).isEmpty();
  }
}
