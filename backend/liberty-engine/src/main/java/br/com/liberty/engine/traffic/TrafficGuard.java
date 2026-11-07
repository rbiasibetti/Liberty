package br.com.liberty.engine.traffic;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Admissão de execuções por tenant: taxa (balde de fichas, por plano) e simultaneidade (bulkhead).
 * Um tenant que passa do seu plano recebe 429 sem consumir conexão, thread de banco nem trabalhador
 * de script, então os demais tenants não sentem. As recusas são contadas por tenant e motivo
 * ({@code liberty.traffic.rejected}).
 */
public final class TrafficGuard {

  /** Vaga de simultaneidade; fechar devolve a vaga. */
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  private static final Permit NONE = () -> {};

  private final RateLimiter limiter;
  private final LibertyLimitProperties properties;
  private final Function<String, String> planOf;
  private final MeterRegistry meters;
  private final Cache<String, PlanLimits> plans;
  private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();

  public TrafficGuard(
      RateLimiter limiter,
      LibertyLimitProperties properties,
      Function<String, String> planOf,
      MeterRegistry meters) {
    this.limiter = limiter;
    this.properties = properties;
    this.planOf = planOf;
    this.meters = meters;
    this.plans =
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(properties.planCacheTtl())
            .build();
  }

  /** Admite a execução ou lança 429; o chamador fecha a vaga ao terminar. */
  public Permit admit(TenantScope scope) {
    String tenant = scope.tenant().value();
    PlanLimits limits = plans.get(tenant, t -> properties.limits(planOf.apply(t)));
    RateLimiter.Decision decision = limiter.tryAcquire(tenant, limits);
    if (!decision.allowed()) {
      rejected(tenant, "rate");
      throw new ProblemException(
          429,
          "rate-limited",
          "Muitas requisições",
          "O limite de requisições do plano foi atingido",
          List.of(),
          Map.of(
              "Retry-After", Long.toString(decision.retryAfterSeconds()),
              "RateLimit-Limit", Long.toString(decision.limit()),
              "RateLimit-Remaining", "0"));
    }
    String key = tenant + ":" + limits.maxConcurrent();
    Semaphore bulkhead = bulkheads.computeIfAbsent(key, k -> new Semaphore(limits.maxConcurrent()));
    if (!bulkhead.tryAcquire()) {
      rejected(tenant, "concurrency");
      throw new ProblemException(
          429,
          "tenant-busy",
          "Muitas execuções simultâneas",
          "O tenant atingiu o limite de execuções simultâneas do plano",
          List.of(),
          Map.of("Retry-After", "1"));
    }
    return new Permit() {
      private boolean closed;

      @Override
      public synchronized void close() {
        if (!closed) {
          closed = true;
          bulkhead.release();
        }
      }
    };
  }

  /** Sem limites (testes e execuções internas). */
  public static Permit none() {
    return NONE;
  }

  private void rejected(String tenant, String reason) {
    meters.counter("liberty.traffic.rejected", "tenant", tenant, "reason", reason).increment();
  }
}
