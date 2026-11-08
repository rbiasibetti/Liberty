package br.com.liberty.engine;

import br.com.liberty.engine.audit.AuditMaintenance;
import br.com.liberty.engine.audit.AuditRecorder;
import br.com.liberty.engine.audit.LibertyAuditProperties;
import br.com.liberty.engine.exports.ExportHandler;
import br.com.liberty.engine.exports.ExportLinks;
import br.com.liberty.engine.exports.ExportProperties;
import br.com.liberty.engine.exports.ExportRepository;
import br.com.liberty.engine.exports.ExportService;
import br.com.liberty.engine.exports.LocalObjectStore;
import br.com.liberty.engine.exports.ObjectStore;
import br.com.liberty.engine.lifecycle.LifecycleService;
import br.com.liberty.engine.outbound.HostAllowList;
import br.com.liberty.engine.outbound.OutboundHttp;
import br.com.liberty.engine.outbound.OutboundProperties;
import br.com.liberty.engine.outbound.RepositoryHostAllowList;
import br.com.liberty.engine.queue.QueueAdmin;
import br.com.liberty.engine.queue.QueueDispatcher;
import br.com.liberty.engine.queue.QueueHandler;
import br.com.liberty.engine.queue.QueueProperties;
import br.com.liberty.engine.queue.QueueRepository;
import br.com.liberty.engine.queue.QueueService;
import br.com.liberty.engine.queue.WebhookHandler;
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
  OutboundProperties.class,
  QueueProperties.class,
  ExportProperties.class
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
  QueueRepository queueRepository(javax.sql.DataSource coreDataSource) {
    return new QueueRepository(new org.springframework.jdbc.core.JdbcTemplate(coreDataSource));
  }

  @Bean
  QueueHandler webhookQueueHandler(OutboundHttp outbound) {
    return new WebhookHandler(outbound);
  }

  @Bean
  QueueService queueService(
      QueueRepository repository,
      QueueProperties properties,
      java.util.List<QueueHandler> handlers,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new QueueService(repository, properties, handlers, meters);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "liberty.queue.enabled",
      matchIfMissing = true)
  QueueDispatcher queueDispatcher(
      QueueRepository repository,
      QueueProperties properties,
      java.util.List<QueueHandler> handlers,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new QueueDispatcher(repository, properties, handlers, meters);
  }

  @Bean
  ExportRepository exportRepository(javax.sql.DataSource coreDataSource) {
    return new ExportRepository(new org.springframework.jdbc.core.JdbcTemplate(coreDataSource));
  }

  @Bean
  ObjectStore exportStore(ExportProperties properties) throws java.io.IOException {
    return new LocalObjectStore(java.nio.file.Path.of(properties.directory()));
  }

  @Bean
  ExportLinks exportLinks(ExportProperties properties) {
    byte[] key;
    if (properties.signingKey() == null || properties.signingKey().isBlank()) {
      key = new byte[32];
      new java.security.SecureRandom().nextBytes(key);
      org.slf4j.LoggerFactory.getLogger(EngineConfig.class)
          .warn(
              "liberty.exports.signing-key não definida: links de download valem só nesta instância e até reiniciar");
    } else {
      key = properties.signingKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    return new ExportLinks(key, java.time.Clock.systemUTC());
  }

  @Bean
  QueueHandler exportQueueHandler(
      ExportRepository repository,
      ObjectStore exportStore,
      PublishedRoutes routes,
      ReadEngine reads,
      ExportProperties properties,
      QueueProperties queueProperties) {
    return new ExportHandler(
        repository,
        exportStore,
        routes,
        reads,
        properties,
        queueProperties,
        java.time.Clock.systemUTC());
  }

  @Bean
  ExportService exportService(
      ExportRepository repository,
      ObjectStore exportStore,
      ExportLinks links,
      PublishedRoutes routes,
      QueueService queues,
      ExportProperties properties) {
    return new ExportService(
        repository, exportStore, links, routes, queues, properties, java.time.Clock.systemUTC());
  }

  @Bean
  QueueAdmin queueAdmin(QueueRepository repository) {
    return new QueueAdmin(repository);
  }

  @Bean
  ScriptHooks scriptHooks(
      WorkerPool pool,
      TenantCatalog catalog,
      @Qualifier("tenantJdbcTemplate") NamedParameterJdbcTemplate jdbc,
      LibertyScriptProperties properties,
      OutboundHttp outbound,
      QueueService queues) {
    return new WorkerScriptHooks(pool, catalog, jdbc, properties, outbound, queues);
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
