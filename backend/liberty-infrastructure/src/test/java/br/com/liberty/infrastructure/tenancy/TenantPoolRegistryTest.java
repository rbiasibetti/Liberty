package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import com.github.benmanes.caffeine.cache.Ticker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Roteamento por tenant contra SQL Server real: um banco por tenant, sem mistura de conexões. */
class TenantPoolRegistryTest {

  private static final int TENANTS = 50;
  private static final Map<TenantScope, CoreTestDatabase.Scratch> DATABASES =
      new ConcurrentHashMap<>();
  private static final List<TenantScope> SCOPES = new ArrayList<>();

  private static final Map<TenantScope, TenantConnectionInfo> CATALOG = new ConcurrentHashMap<>();
  private static final AtomicInteger SECRET_READS = new AtomicInteger();
  private static String adminPassword;

  private static TenantPoolRegistry registry;
  private static NamedParameterJdbcTemplate jdbc;

  @BeforeAll
  static void createTenantDatabases() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < TENANTS; i++) {
        TenantScope scope = new TenantScope(new TenantId("tenant-" + i), EnvironmentKind.HML);
        SCOPES.add(scope);
        futures.add(
            executor.submit(
                () -> {
                  var db = CoreTestDatabase.create();
                  try (Connection c =
                          DriverManager.getConnection(db.url(), db.user(), db.password());
                      Statement st = c.createStatement()) {
                    st.execute(
                        "CREATE TABLE marker (tenant VARCHAR(63) NOT NULL, hits INT NOT NULL DEFAULT 0)");
                    st.execute("INSERT INTO marker (tenant) VALUES ('" + scope.tenant() + "')");
                  }
                  adminPassword = db.password();
                  DATABASES.put(scope, db);
                  CATALOG.put(
                      scope,
                      new TenantConnectionInfo(db.url(), db.user(), "ref/" + scope.tenant(), 3));
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }
    registry =
        new TenantPoolRegistry(
            scope -> Optional.ofNullable(CATALOG.get(scope)),
            reference -> {
              SECRET_READS.incrementAndGet();
              return adminPassword;
            },
            Duration.ofMinutes(30));
    jdbc = new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
  }

  @AfterAll
  static void closePools() {
    registry.destroy();
  }

  @AfterEach
  void clearContext() {
    TenantContext.clear();
  }

  private static String marker(TenantScope scope) {
    return TenantContext.supplyAs(
        scope,
        () -> jdbc.getJdbcTemplate().queryForObject("SELECT tenant FROM marker", String.class));
  }

  @Test
  void fiftyTenantsUnderConcurrencyNeverMixConnections() throws Exception {
    AtomicInteger mismatches = new AtomicInteger();
    AtomicLong queries = new AtomicLong();
    try (ExecutorService executor = Executors.newFixedThreadPool(24)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 24; t++) {
        futures.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < 150; i++) {
                    TenantScope scope = SCOPES.get(ThreadLocalRandom.current().nextInt(TENANTS));
                    String seen = marker(scope);
                    String db =
                        TenantContext.supplyAs(
                            scope,
                            () ->
                                jdbc.getJdbcTemplate()
                                    .queryForObject("SELECT DB_NAME()", String.class));
                    queries.addAndGet(2);
                    if (!seen.equals(scope.tenant().value())
                        || !db.equals(DATABASES.get(scope).name())) {
                      mismatches.incrementAndGet();
                    }
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(queries.get()).isEqualTo(24L * 150 * 2);
    assertThat(mismatches.get()).isZero();
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void eachTenantGetsItsOwnPoolWithTheConfiguredLimits() {
    TenantScope scope = SCOPES.get(0);
    marker(scope);

    var pool = registry.pool(scope);

    assertThat(registry.pool(scope)).isSameAs(pool);
    assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
    assertThat(pool.getMinimumIdle()).isZero();
    assertThat(pool.getPoolName()).isEqualTo("tenant-tenant-0-hml");
    assertThat(registry.pool(SCOPES.get(1))).isNotSameAs(pool);
  }

  @Test
  void transactionsAreBoundToTheTenantOfTheContext() {
    TenantScope a = SCOPES.get(2);
    TenantScope b = SCOPES.get(3);
    var tx =
        new TransactionTemplate(
            new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));

    TenantContext.runAs(
        a,
        () ->
            tx.executeWithoutResult(
                s -> {
                  jdbc.getJdbcTemplate().update("UPDATE marker SET hits = hits + 100");
                  s.setRollbackOnly();
                }));
    TenantContext.runAs(
        b,
        () ->
            tx.executeWithoutResult(
                s -> jdbc.getJdbcTemplate().update("UPDATE marker SET hits = hits + 7")));

    assertThat(hits(a)).isZero();
    assertThat(hits(b)).isEqualTo(7);
    assertThat(hits(SCOPES.get(4))).isZero();
  }

  private static int hits(TenantScope scope) {
    return TenantContext.supplyAs(
        scope,
        () -> jdbc.getJdbcTemplate().queryForObject("SELECT hits FROM marker", Integer.class));
  }

  @Test
  void withoutTenantInTheContextNoConnectionIsGiven() {
    assertThatThrownBy(() -> jdbc.getJdbcTemplate().queryForObject("SELECT 1", Integer.class))
        .hasRootCauseInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new TenantRoutingDataSource(registry).getConnection("u", "p"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void unknownOrInactiveTenantsAreUnavailableAndFailuresAreNotCached() {
    TenantScope ghost = new TenantScope(new TenantId("fantasma"), EnvironmentKind.PRD);

    assertThatThrownBy(() -> registry.pool(ghost)).isInstanceOf(TenantUnavailableException.class);
    assertThat(registry.size()).isGreaterThanOrEqualTo(0);

    CATALOG.put(ghost, CATALOG.get(SCOPES.get(5)));
    assertThat(marker(ghost)).isEqualTo(SCOPES.get(5).tenant().value());
    registry.evict(ghost);
    CATALOG.remove(ghost);
  }

  @Test
  void secretFailuresBecomeUnavailable() {
    TenantScope scope = new TenantScope(new TenantId("sem-segredo"), EnvironmentKind.HML);
    var failing =
        new TenantPoolRegistry(
            s -> Optional.of(CATALOG.get(SCOPES.get(6))),
            reference -> {
              throw new TenantUnavailableException("cofre fora");
            },
            Duration.ofMinutes(1));

    assertThatThrownBy(() -> failing.pool(scope))
        .isInstanceOf(TenantUnavailableException.class)
        .hasMessage("cofre fora");
    assertThat(failing.size()).isZero();
    failing.destroy();
  }

  @Test
  void connectionFailuresDoNotExposeCredentials() {
    TenantScope scope = new TenantScope(new TenantId("porta-errada"), EnvironmentKind.HML);
    var bad =
        new TenantConnectionInfo(
            "jdbc:sqlserver://127.0.0.1:1;databaseName=x;encrypt=true;trustServerCertificate=true",
            "u",
            "ref/x",
            1);
    var isolated =
        new TenantPoolRegistry(
            s -> Optional.of(bad), r -> "SenhaSecreta#123", Duration.ofMinutes(1));

    assertThatThrownBy(() -> isolated.pool(scope))
        .isInstanceOf(TenantUnavailableException.class)
        .satisfies(
            e ->
                assertThat(String.valueOf(e.getMessage()) + e.getCause())
                    .doesNotContain("SenhaSecreta#123"));
    assertThat(isolated.size()).isZero();
    assertThat(bad.toString()).doesNotContain("jdbc:").doesNotContain("u,");
    isolated.destroy();
  }

  @Test
  void idlePoolsAreClosedButBusyOnesSurvive() throws SQLException {
    TenantScope idleScope = SCOPES.get(7);
    TenantScope busyScope = SCOPES.get(8);
    var clock = new AtomicLong();
    Ticker ticker = clock::get;
    var local =
        new TenantPoolRegistry(
            s -> Optional.of(CATALOG.get(s)), r -> adminPassword, Duration.ofMinutes(30), ticker);

    var idle = local.pool(idleScope);
    var busy = local.pool(busyScope);
    try (Connection held = busy.getConnection()) {
      clock.addAndGet(Duration.ofMinutes(31).toNanos());
      local.cleanUp();

      assertThat(idle.isClosed()).isTrue();
      assertThat(busy.isClosed()).isFalse();
      assertThat(local.size()).isEqualTo(1);
      assertThat(held.isValid(2)).isTrue();
    }

    clock.addAndGet(Duration.ofMinutes(31).toNanos());
    local.cleanUp();
    assertThat(busy.isClosed()).isTrue();
    assertThat(local.size()).isZero();
    local.destroy();
  }

  @Test
  void evictClosesThePoolAndANewOneIsCreatedOnDemand() {
    TenantScope scope = SCOPES.get(9);
    var first = registry.pool(scope);
    int reads = SECRET_READS.get();

    registry.evict(scope);
    registry.cleanUp();
    var second = registry.pool(scope);

    assertThat(first.isClosed()).isTrue();
    assertThat(second).isNotSameAs(first);
    assertThat(SECRET_READS.get()).isGreaterThan(reads);
    assertThat(marker(scope)).isEqualTo(scope.tenant().value());
  }
}
