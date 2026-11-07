package br.com.liberty.engine;

import br.com.liberty.engine.audit.AuditMaintenance;
import br.com.liberty.engine.audit.AuditRecorder;
import br.com.liberty.engine.audit.LibertyAuditProperties;
import br.com.liberty.engine.lifecycle.LifecycleService;
import br.com.liberty.engine.outbound.HostAllowList;
import br.com.liberty.engine.outbound.OutboundHttp;
import br.com.liberty.engine.outbound.OutboundProperties;
import br.com.liberty.engine.outbound.RepositoryHostAllowList;
import br.com.liberty.engine.read.ReadEngine;
import br.com.liberty.engine.route.PublicationSource;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.engine.sandbox.SandboxService;
import br.com.liberty.engine.script.LibertyScriptProperties;
import br.com.liberty.engine.script.ScriptHooks;
import br.com.liberty.engine.script.WorkerScriptHooks;
import br.com.liberty.engine.traffic.Bucket4jRateLimiter;
import br.com.liberty.engine.traffic.LibertyLimitProperties;
import br.com.liberty.engine.traffic.TrafficGuard;
import br.com.liberty.engine.write.FieldTransformer;
import br.com.liberty.engine.write.IdempotencyService;
import br.com.liberty.engine.write.JpaIdempotencyService;
import br.com.liberty.engine.write.Transforms;
import br.com.liberty.engine.write.WriteEngine;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.persistence.IdempotencyRepository;
import br.com.liberty.infrastructure.persistence.IntegrationRepository;
import br.com.liberty.infrastructure.persistence.IntegrationVersionRepository;
import br.com.liberty.infrastructure.persistence.PublicationRepository;
import br.com.liberty.infrastructure.persistence.TenantHttpHostRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.scripts.pool.WorkerPool;
import br.com.liberty.snapshot.SnapshotService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({
  LibertyEngineProperties.class,
  LibertyScriptProperties.class,
  LibertyAuditProperties.class,
  LibertyLimitProperties.class,
  OutboundProperties.class
})
public class EngineConfig {

  @Bean
  SnapshotService snapshotService() {
    return new SnapshotService();
  }

  @Bean
  PublishedRoutes publishedRoutes(PublicationSource source, LibertyEngineProperties properties) {
    return new PublishedRoutes(source, properties.routesRefreshInterval());
  }

  /** Leituras rodam em transação somente leitura no banco do tenant, com o prazo das consultas. */
  @Bean
  ReadEngine readEngine(
      TenantCatalog catalog,
      @Qualifier("tenantJdbcTemplate") NamedParameterJdbcTemplate jdbc,
      @Qualifier("tenantTransactionManager") PlatformTransactionManager transactionManager,
      LibertyEngineProperties properties) {
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    readOnly.setTimeout((int) properties.queryTimeout().toSeconds());
    return new ReadEngine(catalog, jdbc, readOnly);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  FieldTransformer fieldTransformer() {
    return new Transforms();
  }

  /** Reservas de idempotência vivem no core: transação do core, não a do tenant. */
  @Bean
  IdempotencyService idempotencyService(
      IdempotencyRepository repository,
      TenantRepository tenants,
      @Qualifier("transactionManager") PlatformTransactionManager coreTransactionManager,
      LibertyEngineProperties properties) {
    return new JpaIdempotencyService(
        repository,
        tenants,
        new TransactionTemplate(coreTransactionManager),
        properties.idempotencyTtl());
  }

  /** Uma escrita é uma única transação no banco do tenant, com prazo total. */
  @Bean
  WriteEngine writeEngine(
      TenantCatalog catalog,
      @Qualifier("tenantJdbcTemplate") NamedParameterJdbcTemplate jdbc,
      @Qualifier("tenantTransactionManager") PlatformTransactionManager transactionManager,
      IdempotencyService idempotency,
      FieldTransformer transformer,
      ScriptHooks scripts,
      LibertyEngineProperties properties) {
    TransactionTemplate writes = new TransactionTemplate(transactionManager);
    writes.setTimeout((int) properties.writeTimeout().toSeconds());
    return new WriteEngine(
        catalog, jdbc, writes, idempotency, transformer, properties.maxWriteRows(), scripts);
  }

  /** Pool de processos trabalhadores; só sobem processos quando o primeiro script é executado. */
  @Bean(destroyMethod = "close")
  WorkerPool scriptWorkerPool(LibertyScriptProperties p) {
    String classpath =
        p.workerClasspath() == null || p.workerClasspath().isBlank()
            ? System.getProperty("java.class.path")
            : p.workerClasspath();
    return new WorkerPool(
        new WorkerPool.Config(
            p.poolSize(),
            p.heap(),
            p.queueWait(),
            p.killGrace(),
            p.recycleRuns(),
            p.recycleAge(),
            classpath,
            p.maxHostCalls()));
  }

  @Bean
  HostAllowList httpHostAllowList(TenantHttpHostRepository repository) {
    return new RepositoryHostAllowList(repository);
  }

  @Bean(destroyMethod = "close")
  OutboundHttp outboundHttp(OutboundProperties properties, HostAllowList allowList) {
    return new OutboundHttp(properties, allowList);
  }

  @Bean
  ScriptHooks scriptHooks(
      WorkerPool pool,
      TenantCatalog catalog,
      @Qualifier("tenantJdbcTemplate") NamedParameterJdbcTemplate jdbc,
      LibertyScriptProperties properties,
      OutboundHttp outbound) {
    return new WorkerScriptHooks(pool, catalog, jdbc, properties, outbound);
  }

  @Bean
  LifecycleService lifecycleService(
      TenantRepository tenants,
      IntegrationRepository integrations,
      IntegrationVersionRepository versions,
      PublicationRepository publications,
      SnapshotService snapshots,
      TenantCatalog catalog,
      PublishedRoutes routes,
      jakarta.persistence.EntityManager entityManager) {
    return new LifecycleService(
        tenants, integrations, versions, publications, snapshots, catalog, routes, entityManager);
  }

  @Bean
  SandboxService sandboxService(
      LifecycleService lifecycle,
      ReadEngine reads,
      WriteEngine writes,
      ScriptHooks scripts,
      SnapshotService snapshots) {
    return new SandboxService(lifecycle, reads, writes, scripts, snapshots);
  }

  /** Grava no banco central, em lote e fora do caminho da requisição. */
  @Bean(destroyMethod = "close")
  AuditRecorder auditRecorder(
      javax.sql.DataSource coreDataSource,
      io.micrometer.core.instrument.MeterRegistry meters,
      LibertyAuditProperties properties) {
    return new AuditRecorder(
        new org.springframework.jdbc.core.JdbcTemplate(coreDataSource),
        meters,
        properties.queueCapacity(),
        properties.batchSize(),
        properties.flushInterval());
  }

  /** Métricas do pool de scripts. */
  @Bean
  io.micrometer.core.instrument.binder.MeterBinder scriptPoolMetrics(WorkerPool pool) {
    return registry ->
        io.micrometer.core.instrument.Gauge.builder(
                "liberty.script.workers", pool, WorkerPool::workers)
            .description("Processos trabalhadores de script vivos")
            .register(registry);
  }

  @Bean
  AuditMaintenance auditMaintenance(
      javax.sql.DataSource coreDataSource, LibertyAuditProperties properties) {
    return new AuditMaintenance(
        new org.springframework.jdbc.core.JdbcTemplate(coreDataSource), properties);
  }

  @Bean(destroyMethod = "close")
  Bucket4jRateLimiter rateLimiter(
      LibertyLimitProperties properties, io.micrometer.core.instrument.MeterRegistry meters) {
    return new Bucket4jRateLimiter(properties.redisUri(), properties.redisKeyPrefix(), meters);
  }

  /** Admissão por tenant: o plano vem de {@code int_tenant.plan_code}, com cache. */
  @Bean
  TrafficGuard trafficGuard(
      Bucket4jRateLimiter limiter,
      LibertyLimitProperties properties,
      javax.sql.DataSource coreDataSource,
      io.micrometer.core.instrument.MeterRegistry meters) {
    var jdbc = new org.springframework.jdbc.core.JdbcTemplate(coreDataSource);
    return new TrafficGuard(
        limiter,
        properties,
        slug -> {
          var found =
              jdbc.queryForList(
                  "SELECT plan_code FROM int_tenant WHERE slug = ?", String.class, slug);
          return found.isEmpty() ? "STANDARD" : found.getFirst();
        },
        meters);
  }
}
