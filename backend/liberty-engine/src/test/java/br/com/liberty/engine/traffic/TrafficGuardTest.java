package br.com.liberty.engine.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TrafficGuardTest {

  private static TenantScope scope(String tenant) {
    return new TenantScope(new TenantId(tenant), EnvironmentKind.HML);
  }

  private static LibertyLimitProperties limits(int rate, int burst, int concurrent) {
    return new LibertyLimitProperties(
        rate,
        burst,
        concurrent,
        Duration.ofMinutes(1),
        "t:",
        null,
        Map.of("PREMIUM", new LibertyLimitProperties.Plan(500, 1000, null)));
  }

  private static TrafficGuard guard(
      LibertyLimitProperties props, java.util.function.Function<String, String> plans) {
    var meters = new SimpleMeterRegistry();
    return new TrafficGuard(new Bucket4jRateLimiter(meters), props, plans, meters);
  }

  @Test
  void aTenantOverItsBurstGetsA429WithRetryHeadersAndRefillsOverTime() throws Exception {
    var guard = guard(limits(5, 5, 100), t -> "STANDARD");
    for (int i = 0; i < 5; i++) {
      guard.admit(scope("acme")).close();
    }

    ProblemException e =
        catchThrowableOfType(ProblemException.class, () -> guard.admit(scope("acme")));

    assertThat(e.status()).isEqualTo(429);
    assertThat(e.type()).endsWith("rate-limited");
    assertThat(e.headers()).containsKeys("Retry-After", "RateLimit-Limit", "RateLimit-Remaining");
    assertThat(Integer.parseInt(e.headers().get("Retry-After"))).isGreaterThanOrEqualTo(1);
    assertThat(e.headers()).containsEntry("RateLimit-Limit", "5");
    Thread.sleep(1_100);
    guard.admit(scope("acme")).close(); // as fichas voltaram
  }

  @Test
  void oneTenantExhaustingItsLimitNeverTouchesAnother() {
    var guard = guard(limits(5, 5, 100), t -> "STANDARD");
    for (int i = 0; i < 50; i++) {
      try {
        guard.admit(scope("barulhento")).close();
      } catch (ProblemException expected) {
        // recusado, como esperado
      }
    }

    for (int i = 0; i < 5; i++) {
      guard.admit(scope("vizinho")).close();
    }
  }

  @Test
  void concurrencyIsCappedPerTenantAndPermitsAreReturned() {
    var guard = guard(limits(1000, 1000, 2), t -> "STANDARD");
    var first = guard.admit(scope("acme"));
    var second = guard.admit(scope("acme"));

    ProblemException busy =
        catchThrowableOfType(ProblemException.class, () -> guard.admit(scope("acme")));
    var other = guard.admit(scope("beta")); // outro tenant não divide a vaga

    assertThat(busy.status()).isEqualTo(429);
    assertThat(busy.type()).endsWith("tenant-busy");
    first.close();
    first.close(); // fechar duas vezes não devolve duas vagas
    var again = guard.admit(scope("acme"));
    assertThat(catchThrowableOfType(ProblemException.class, () -> guard.admit(scope("acme"))))
        .isNotNull();
    second.close();
    again.close();
    other.close();
  }

  @Test
  void plansOverrideTheDefaultsAndUnknownPlansUseThem() {
    var props = limits(10, 20, 7);

    assertThat(props.limits("PREMIUM")).isEqualTo(new PlanLimits(500, 1000, 7));
    assertThat(props.limits("OUTRO")).isEqualTo(new PlanLimits(10, 20, 7));
    assertThat(new PlanLimits(0, 0, 0)).isEqualTo(new PlanLimits(1, 1, 1));
    var guard = guard(props, t -> t.equals("rico") ? "PREMIUM" : "STANDARD");
    int accepted = 0;
    for (int i = 0; i < 100; i++) {
      try {
        guard.admit(scope("rico")).close();
        accepted++;
      } catch (ProblemException e) {
        // não deveria acontecer: o plano permite rajada de 1000
      }
    }
    assertThat(accepted).isEqualTo(100);
  }

  @Test
  void rejectionsAreMeteredByTenantAndReason() {
    var meters = new SimpleMeterRegistry();
    var guard =
        new TrafficGuard(new Bucket4jRateLimiter(meters), limits(1, 1, 1), t -> "STANDARD", meters);
    guard.admit(scope("acme")).close();
    catchThrowableOfType(ProblemException.class, () -> guard.admit(scope("acme")));

    assertThat(
            meters
                .get("liberty.traffic.rejected")
                .tag("tenant", "acme")
                .tag("reason", "rate")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(TrafficGuard.none()).isNotNull();
  }

  @Test
  void anUnreachableRedisFallsBackToLocalBucketsAndIsCounted() {
    var meters = new SimpleMeterRegistry();
    try (var limiter = new Bucket4jRateLimiter("redis://127.0.0.1:1", "t:", meters)) {
      var limits = new PlanLimits(2, 2, 10);
      assertThat(limiter.tryAcquire("acme", limits).allowed()).isTrue();
      assertThat(limiter.tryAcquire("acme", limits).allowed()).isTrue();
      assertThat(limiter.tryAcquire("acme", limits).allowed())
          .as("o balde local ainda limita")
          .isFalse();
      assertThat(meters.get("liberty.ratelimit.fallback").counter().count()).isEqualTo(3.0);
    }
  }

  @Test
  void aSharedRedisBucketLimitsTheSumOfAllInstances() {
    String uri = System.getenv("LIBERTY_TEST_REDIS_URI");
    assumeTrue(
        uri != null && !uri.isBlank(), "defina LIBERTY_TEST_REDIS_URI para testar o Redis real");
    var meters = new SimpleMeterRegistry();
    String tenant = "redis-" + System.nanoTime();
    var limits = new PlanLimits(1, 10, 10);
    AtomicInteger allowed = new AtomicInteger();
    try (var one = new Bucket4jRateLimiter(uri, "liberty:test:", meters);
        var two = new Bucket4jRateLimiter(uri, "liberty:test:", meters)) {
      for (int i = 0; i < 8; i++) {
        if (one.tryAcquire(tenant, limits).allowed()) allowed.incrementAndGet();
        if (two.tryAcquire(tenant, limits).allowed()) allowed.incrementAndGet();
      }
    }
    assertThat(allowed.get()).as("duas instâncias dividem um só balde de 10").isBetween(10, 11);
  }
}
