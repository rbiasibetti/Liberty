package br.com.liberty.engine.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Filas por tenant sobre um banco central de verdade: entrega, isolamento entre tenants (critério
 * da E23), recuo e mensagens mortas, arrendamento, publicação adiada até o commit e administração.
 */
class QueueTest {

  private static final QueueProperties PROPS =
      new QueueProperties(
          true,
          Duration.ofMillis(50),
          8,
          2,
          3,
          Duration.ZERO,
          Duration.ofSeconds(1),
          Duration.ofSeconds(60),
          512,
          6,
          3,
          Duration.ofDays(30));

  private static JdbcTemplate jdbc;
  private static QueueRepository repository;
  private static final Map<String, Long> TENANTS = new ConcurrentHashMap<>();

  @BeforeAll
  static void migrate() {
    var db = CoreTestDatabase.create();
    Flyway.configure()
        .dataSource(db.url(), db.user(), db.password())
        .locations("classpath:db/migration/core")
        .table("int_flyway_schema_history")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url(), db.user(), db.password()));
    repository = new QueueRepository(jdbc);
  }

  @AfterAll
  static void stop() {
    CoreTestDatabase.dropAll();
  }

  private static long tenant(String slug) {
    return TENANTS.computeIfAbsent(
        slug,
        s -> {
          jdbc.update("INSERT INTO int_tenant (slug, name) VALUES (?, ?)", s, s);
          return jdbc.queryForObject("SELECT id FROM int_tenant WHERE slug = ?", Long.class, s);
        });
  }

  private static void enqueue(String slug, String payload) {
    repository.enqueue(tenant(slug), "HML", "webhook", payload, null);
  }

  private static long count(String slug, String status) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM int_queue_message WHERE tenant_id = ? AND status = ?",
        Long.class,
        tenant(slug),
        status);
  }

  private static long total(String slug) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM int_queue_message WHERE tenant_id = ?", Long.class, tenant(slug));
  }

  private static QueueHandler handler(Consumer<QueueEnvelope> action) {
    return new QueueHandler() {
      @Override
      public String queue() {
        return "webhook";
      }

      @Override
      public void handle(QueueEnvelope message) {
        action.accept(message);
      }
    };
  }

  private static QueueDispatcher dispatcher(QueueHandler handler) {
    return new QueueDispatcher(repository, PROPS, List.of(handler), new SimpleMeterRegistry());
  }

  private static void until(java.util.function.BooleanSupplier condition, QueueDispatcher d)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      d.poll();
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).as("condição atendida a tempo").isTrue();
  }

  // ---- entrega ------------------------------------------------------------------------

  @Test
  void consumersReceiveTheTenantEnvelopeAndRunInThatTenantContext() throws Exception {
    var seen = new ConcurrentHashMap<String, String>();
    var d =
        dispatcher(
            handler(
                m -> {
                  seen.put(
                      m.tenant(),
                      m.environment()
                          + "|"
                          + m.attempt()
                          + "|"
                          + m.queue()
                          + "|"
                          + m.payload().path("n").asInt()
                          + "|"
                          + TenantContext.require().tenant().value()
                          + "|"
                          + TenantContext.require().environment());
                }));
    repository.enqueue(tenant("env-a"), "PRD", "webhook", "{\"n\":7}", "req-1");

    until(() -> total("env-a") == 0, d);

    assertThat(seen).containsEntry("env-a", "PRD|1|webhook|7|env-a|PRD");
  }

  // ---- critério da E23: um tenant com problema não bloqueia a fila ---------------------

  @Test
  void aStuckTenantOnlyConsumesItsOwnSlotsAndOthersKeepFlowing() throws Exception {
    var release = new CountDownLatch(1);
    var inFlight = new AtomicInteger();
    var peak = new AtomicInteger();
    var done = new ConcurrentHashMap<String, AtomicInteger>();
    var d =
        dispatcher(
            handler(
                m -> {
                  if (m.tenant().equals("travado")) {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                      release.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } finally {
                      inFlight.decrementAndGet();
                    }
                  }
                  done.computeIfAbsent(m.tenant(), t -> new AtomicInteger()).incrementAndGet();
                }));
    for (int i = 0; i < 6; i++) {
      enqueue("travado", "{\"i\":" + i + "}");
      enqueue("saudavel", "{\"i\":" + i + "}");
    }

    long start = System.nanoTime();
    until(() -> done.getOrDefault("saudavel", new AtomicInteger()).get() == 6, d);
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertThat(millis).as("o tenant saudável não esperou o travado").isLessThan(5_000);
    assertThat(count("travado", "PROCESSING")).isEqualTo(2);
    assertThat(count("travado", "READY")).isEqualTo(4);
    assertThat(peak.get()).as("bulkhead por tenant").isLessThanOrEqualTo(2);
    assertThat(total("saudavel")).isZero();

    release.countDown();
    until(() -> total("travado") == 0, d);
    assertThat(done.get("travado").get()).isEqualTo(6);
  }

  @Test
  void poisonMessagesGoToTheDeadLetterQueueWithoutBlockingTheGoodOnes() throws Exception {
    var ok = new AtomicInteger();
    var attemptsSeen = new ConcurrentHashMap<Integer, AtomicInteger>();
    var d =
        dispatcher(
            handler(
                m -> {
                  if (m.payload().path("veneno").asBoolean()) {
                    attemptsSeen
                        .computeIfAbsent(m.attempt(), a -> new AtomicInteger())
                        .incrementAndGet();
                    throw new IllegalStateException("destino fora do ar");
                  }
                  ok.incrementAndGet();
                }));
    for (int i = 0; i < 3; i++) {
      enqueue("veneno-a", "{\"veneno\":true}");
    }
    for (int i = 0; i < 4; i++) {
      enqueue("veneno-a", "{\"veneno\":false}");
    }

    until(() -> count("veneno-a", "DEAD") == 3 && ok.get() == 4, d);

    assertThat(attemptsSeen.keySet()).containsExactlyInAnyOrder(1, 2, 3);
    assertThat(count("veneno-a", "READY") + count("veneno-a", "PROCESSING")).isZero();
    var dead = repository.dead(tenant("veneno-a"), 10);
    assertThat(dead).hasSize(3);
    assertThat(dead.getFirst().attempts()).isEqualTo(3);
    assertThat(dead.getFirst().lastError()).isEqualTo("destino fora do ar");
  }

  @Test
  void permanentFailuresDieAtOnceAndUnknownQueuesHaveNoConsumer() throws Exception {
    var calls = new AtomicInteger();
    var d =
        dispatcher(
            handler(
                m -> {
                  calls.incrementAndGet();
                  throw new QueueHandler.Permanent("Destino recusou com 404");
                }));
    enqueue("permanente", "{}");
    jdbc.update(
        "INSERT INTO int_queue_message (tenant_id, environment, queue_name, payload)"
            + " VALUES (?, 'HML', 'sem-consumidor', '{}')",
        tenant("permanente"));

    until(() -> count("permanente", "DEAD") == 2, d);

    assertThat(calls.get()).isEqualTo(1);
    assertThat(repository.dead(tenant("permanente"), 10))
        .extracting(QueueRepository.Dead::lastError)
        .containsExactlyInAnyOrder("Destino recusou com 404", "Fila sem consumidor");
  }

  @Test
  void backoffGrowsByFourAndIsCapped() {
    var d = dispatcher(handler(m -> {}));
    var slow =
        new QueueDispatcher(
            repository,
            new QueueProperties(
                true,
                Duration.ofMillis(50),
                8,
                2,
                5,
                Duration.ofSeconds(2),
                Duration.ofMinutes(10),
                Duration.ofSeconds(60),
                512,
                6,
                3,
                Duration.ofDays(30)),
            List.of(handler(m -> {})),
            new SimpleMeterRegistry());

    assertThat(slow.backoff(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(slow.backoff(2)).isEqualTo(Duration.ofSeconds(8));
    assertThat(slow.backoff(3)).isEqualTo(Duration.ofSeconds(32));
    assertThat(slow.backoff(4)).isEqualTo(Duration.ofSeconds(128));
    assertThat(slow.backoff(9)).isEqualTo(Duration.ofMinutes(10));
    assertThat(d.backoff(5)).isEqualTo(Duration.ZERO);
    assertThatThrownBy(
            () ->
                new QueueDispatcher(
                    repository,
                    new QueueProperties(
                        true,
                        Duration.ofMillis(50),
                        2,
                        2,
                        5,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ofSeconds(60),
                        512,
                        6,
                        3,
                        Duration.ofDays(1)),
                    List.of(),
                    new SimpleMeterRegistry()))
        .as("o teto por tenant tem de ser menor que o total")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- arrendamento -------------------------------------------------------------------

  @Test
  void expiredLeasesComeBackToTheQueueAndDieWhenAttemptsAreExhausted() throws Exception {
    long id;
    enqueue("lease-a", "{}");
    for (int attempt = 1; attempt <= 3; attempt++) {
      var claimed = repository.claim(tenant("lease-a"), 5, 0);
      assertThat(claimed).hasSize(1);
      assertThat(claimed.getFirst().attempts()).isEqualTo(attempt);
      Thread.sleep(30);
      repository.reclaimExpired(3);
      assertThat(count("lease-a", attempt < 3 ? "READY" : "DEAD")).isEqualTo(1);
    }
    id = repository.dead(tenant("lease-a"), 1).getFirst().id();
    assertThat(repository.requeue(tenant("lease-a"), id)).isTrue();
    assertThat(count("lease-a", "READY")).isEqualTo(1);
  }

  @Test
  void twoConsumersNeverClaimTheSameMessage() throws Exception {
    for (int i = 0; i < 40; i++) {
      enqueue("corrida", "{\"i\":" + i + "}");
    }
    var ids = ConcurrentHashMap.<Long>newKeySet();
    var duplicates = new AtomicInteger();
    Runnable consumer =
        () -> {
          for (int i = 0; i < 40; i++) {
            for (var m : repository.claim(tenant("corrida"), 3, 60)) {
              if (!ids.add(m.id())) {
                duplicates.incrementAndGet();
              }
            }
          }
        };
    var a = new Thread(consumer);
    var b = new Thread(consumer);
    a.start();
    b.start();
    a.join();
    b.join();

    assertThat(duplicates.get()).isZero();
    assertThat(ids).hasSize(40);
  }

  // ---- publicação pelos scripts -------------------------------------------------------

  private static QueueService service() {
    return new QueueService(
        repository, PROPS, List.of(handler(m -> {})), new SimpleMeterRegistry());
  }

  private static final TenantScope PUB =
      new TenantScope(new TenantId("publica"), EnvironmentKind.PRD);

  @Test
  void publishedMessagesAreWrittenOnlyAfterTheCommitAndNeverOnRollbackOrRetry() {
    tenant("publica");
    var service = service();
    var session = service.session(PUB, true, false);

    QueueService.begin();
    session.publish("webhook", "{\"a\":1}");
    assertThat(total("publica")).as("antes do commit nada existe").isZero();
    QueueService.end(); // a transação desfez, ou vai repetir: nada é publicado
    service.flush(PUB);
    assertThat(total("publica")).isZero();

    QueueService.begin();
    service.session(PUB, true, false).publish("webhook", "{\"a\":2}");
    service.flush(PUB);
    QueueService.end();
    assertThat(total("publica")).isEqualTo(1);
    var row =
        jdbc.queryForMap(
            "SELECT environment, queue_name, payload FROM int_queue_message WHERE tenant_id = ?",
            tenant("publica"));
    assertThat(row).containsEntry("environment", "PRD").containsEntry("queue_name", "webhook");
    assertThat(row.get("payload").toString()).contains("\"a\":2");
    service.flush(PUB);
    assertThat(total("publica")).as("o que foi gravado não é gravado de novo").isEqualTo(1);
  }

  @Test
  void theSandboxSimulatesAndTheTenantQueueIsBounded() {
    tenant("limite");
    tenant("publica");
    var scope = new TenantScope(new TenantId("limite"), EnvironmentKind.HML);
    var service = service();
    QueueService.begin();
    try {
      assertThat(service.session(scope, true, true).publish("webhook", "{}"))
          .contains("\"simulated\":true");
      service.flush(scope);
      assertThat(total("limite")).isZero();

      for (int i = 0; i < 6; i++) {
        enqueue("limite", "{}");
      }
      assertThatThrownBy(() -> service.session(scope, true, false).publish("webhook", "{}"))
          .hasMessageContaining("cheia");
    } finally {
      QueueService.end();
    }
    assertThatThrownBy(() -> service.session(PUB, true, false).publish("webhook", "{}"))
        .as("sem tentativa em andamento")
        .hasMessageContaining("indisponível");
  }

  // ---- administração ------------------------------------------------------------------

  @Test
  void adminActionsAreScopedToTheTenantAndHideThePayload() throws Exception {
    enqueue("admin-a", "{\"segredo\":\"x\"}");
    enqueue("admin-b", "{}");
    for (String slug : List.of("admin-a", "admin-b")) {
      var claimed = repository.claim(tenant(slug), 1, 60);
      repository.dead(claimed.getFirst().id(), "falhou");
    }
    var admin = new QueueAdmin(repository);
    long idOfA = admin.dead("admin-a").getFirst().id();

    assertThat(admin.stats("admin-a"))
        .extracting(QueueRepository.Stat::status)
        .containsExactly("DEAD");
    assertThat(admin.dead("admin-a")).hasSize(1);
    assertThat(admin.dead("admin-a").toString()).doesNotContain("segredo");
    assertThatThrownBy(() -> admin.requeue("admin-b", idOfA, "ana"))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> admin.discard("admin-b", idOfA, "ana"))
        .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> admin.stats("não-existe")).isInstanceOf(ProblemException.class);
    assertThat(total("admin-a")).as("nada mudou por tentativa cruzada").isEqualTo(1);

    admin.requeue("admin-a", idOfA, "ana");
    assertThat(count("admin-a", "READY")).isEqualTo(1);
    var claimed = repository.claim(tenant("admin-a"), 1, 60);
    repository.dead(claimed.getFirst().id(), "de novo");
    admin.discard("admin-a", idOfA, "ana");
    assertThat(total("admin-a")).isZero();
  }
}
