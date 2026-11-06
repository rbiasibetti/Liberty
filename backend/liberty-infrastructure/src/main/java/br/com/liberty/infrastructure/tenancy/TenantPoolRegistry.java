package br.com.liberty.infrastructure.tenancy;

import br.com.liberty.domain.tenancy.TenantScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Um pool Hikari por tenant e ambiente, criado na primeira requisição com a senha lida do cofre e
 * descartado depois de um período sem uso. Nunca há limite de tamanho (para não expulsar um tenant
 * ativo) e um pool com conexão em uso não é fechado na expiração.
 */
public final class TenantPoolRegistry implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(TenantPoolRegistry.class);

  private final TenantConnectionResolver resolver;
  private final SecretProvider secrets;
  private final Cache<TenantScope, HikariDataSource> pools;
  private final ExecutorService maintenance =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "tenant-pool-maintenance");
            t.setDaemon(true);
            return t;
          });

  public TenantPoolRegistry(
      TenantConnectionResolver resolver, SecretProvider secrets, Duration idleTtl) {
    this(resolver, secrets, idleTtl, Ticker.systemTicker());
  }

  TenantPoolRegistry(
      TenantConnectionResolver resolver, SecretProvider secrets, Duration idleTtl, Ticker ticker) {
    this.resolver = resolver;
    this.secrets = secrets;
    this.pools =
        Caffeine.newBuilder()
            .expireAfterAccess(idleTtl)
            .ticker(ticker)
            .executor(Runnable::run) // o listener só enfileira o trabalho na thread de manutenção
            .removalListener(
                (TenantScope scope, HikariDataSource pool, RemovalCause cause) ->
                    maintenance.execute(() -> release(scope, pool, cause)))
            .build();
  }

  /**
   * Pool do tenant, criado se preciso. Falhas viram {@link TenantUnavailableException} e não ficam
   * em cache.
   */
  public HikariDataSource pool(TenantScope scope) {
    return pools.get(scope, this::create);
  }

  /** Fecha o pool do tenant (ex.: depois de mudar a conexão ou a senha). */
  public void evict(TenantScope scope) {
    pools.invalidate(scope);
  }

  /** Quantidade de pools abertos (métrica e testes). */
  public long size() {
    return pools.estimatedSize();
  }

  /** Executa a limpeza de expirados e espera os fechamentos terminarem. */
  void cleanUp() {
    pools.cleanUp();
    try {
      maintenance.submit(() -> {}).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    pools.cleanUp();
  }

  private HikariDataSource create(TenantScope scope) {
    TenantConnectionInfo info =
        resolver
            .resolve(scope)
            .orElseThrow(
                () -> new TenantUnavailableException("Tenant ou conexão inativo: " + scope));
    JdbcUrlPolicy.check(info.jdbcUrl());
    String password = secrets.password(info.secretRef());

    HikariConfig config = new HikariConfig();
    config.setPoolName("tenant-" + scope.tenant() + "-" + scope.environment().name().toLowerCase());
    config.setJdbcUrl(info.jdbcUrl());
    config.setUsername(info.username());
    config.setPassword(password);
    config.setMaximumPoolSize(info.poolMax());
    config.setMinimumIdle(0);
    config.setIdleTimeout(60_000);
    config.setMaxLifetime(25 * 60_000L);
    config.setConnectionTimeout(5_000);
    config.setValidationTimeout(2_000);
    config.setInitializationFailTimeout(5_000);
    config.setLeakDetectionThreshold(30_000);
    config.addDataSourceProperty("applicationName", "liberty/" + scope);
    try {
      return new HikariDataSource(config);
    } catch (RuntimeException e) {
      throw new TenantUnavailableException(
          "Não foi possível conectar ao banco do tenant " + scope, e);
    }
  }

  private void release(TenantScope scope, HikariDataSource pool, RemovalCause cause) {
    if (pool == null) {
      return;
    }
    if (cause == RemovalCause.EXPIRED && inUse(pool)) {
      pools
          .asMap()
          .putIfAbsent(scope, pool); // há conexão ativa: o pool continua e o prazo recomeça
      return;
    }
    LOG.info("Fechando o pool {} ({})", pool.getPoolName(), cause);
    pool.close();
  }

  private static boolean inUse(HikariDataSource pool) {
    var bean = pool.getHikariPoolMXBean();
    return bean != null && bean.getActiveConnections() > 0;
  }

  @Override
  public void destroy() {
    pools.asMap().values().forEach(HikariDataSource::close);
    pools.invalidateAll();
    maintenance.shutdown();
  }
}
