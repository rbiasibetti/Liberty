package br.com.liberty.engine.queue;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consome as filas. Isolamento entre tenants: cada tenant tem um teto de mensagens em processamento
 * (bulkhead, sempre menor que o total da instância), a reivindicação é por tenant começando pelo
 * mais antigo, e cada mensagem é independente (nenhuma espera pela anterior). Um tenant com destino
 * lento, quebrado ou com mensagens venenosas só consome as próprias vagas e as próprias tentativas.
 */
public class QueueDispatcher implements SmartLifecycle {

  private static final Logger LOG = LoggerFactory.getLogger(QueueDispatcher.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final int TENANTS_PER_ROUND = 200;

  private final QueueRepository repository;
  private final QueueProperties properties;
  private final Map<String, QueueHandler> handlers = new HashMap<>();
  private final MeterRegistry meters;
  private final Semaphore total;
  private final Map<Long, Semaphore> bulkheads = new ConcurrentHashMap<>();
  private final Map<Long, String> slugs = new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private ScheduledExecutorService scheduler;
  private volatile boolean running;
  private int rounds;

  public QueueDispatcher(
      QueueRepository repository,
      QueueProperties properties,
      List<QueueHandler> handlers,
      MeterRegistry meters) {
    this.repository = repository;
    this.properties = properties;
    handlers.forEach(h -> this.handlers.put(h.queue(), h));
    this.meters = meters;
    this.total = new Semaphore(properties.workers());
    if (properties.tenantConcurrency() >= properties.workers()) {
      throw new IllegalArgumentException(
          "liberty.queue.tenant-concurrency precisa ser menor que liberty.queue.workers");
    }
  }

  /** Uma rodada: devolve quantas mensagens foram entregues aos consumidores. Chamável em testes. */
  public int poll() {
    if (++rounds % 20 == 1) {
      housekeeping();
    }
    int started = 0;
    for (long tenantId : repository.tenantsWithReady(TENANTS_PER_ROUND)) {
      Semaphore bulkhead =
          bulkheads.computeIfAbsent(tenantId, id -> new Semaphore(properties.tenantConcurrency()));
      int wanted = 0;
      while (wanted < properties.tenantConcurrency() && total.tryAcquire()) {
        if (bulkhead.tryAcquire()) {
          wanted++;
        } else {
          total.release();
          break;
        }
      }
      if (wanted == 0) {
        continue;
      }
      List<QueueRepository.Claimed> claimed;
      try {
        claimed = repository.claim(tenantId, wanted, (int) properties.lease().toSeconds());
      } catch (RuntimeException e) {
        release(bulkhead, wanted);
        throw e;
      }
      release(bulkhead, wanted - claimed.size());
      for (QueueRepository.Claimed message : claimed) {
        started++;
        workers.execute(() -> process(message, bulkhead));
      }
    }
    return started;
  }

  private void release(Semaphore bulkhead, int permits) {
    if (permits > 0) {
      bulkhead.release(permits);
      total.release(permits);
    }
  }

  private void housekeeping() {
    try {
      repository.reclaimExpired(properties.maxAttempts());
      repository.purgeDead((int) properties.deadRetention().toDays());
    } catch (RuntimeException e) {
      LOG.warn("Manutenção das filas falhou: {}", e.getClass().getSimpleName());
    }
  }

  private void process(QueueRepository.Claimed claimed, Semaphore bulkhead) {
    try {
      String slug = slug(claimed.tenantId());
      var envelope =
          new QueueEnvelope(
              claimed.id(),
              slug,
              EnvironmentKind.valueOf(claimed.environment()),
              claimed.queue(),
              claimed.attempts(),
              claimed.correlationId(),
              claimed.createdAt(),
              MAPPER.readTree(claimed.payload()));
      QueueHandler handler = handlers.get(claimed.queue());
      if (handler == null) {
        finish(claimed, "dead", "Fila sem consumidor");
        return;
      }
      try {
        TenantContext.runAs(
            new TenantScope(new TenantId(slug), envelope.environment()),
            () -> handler.handle(envelope));
        repository.complete(claimed.id());
        count(claimed.queue(), "done");
      } catch (QueueHandler.Permanent e) {
        finish(claimed, "dead", safe(e));
      } catch (RuntimeException e) {
        if (claimed.attempts() >= properties.maxAttempts()) {
          finish(claimed, "dead", safe(e));
        } else {
          repository.retry(claimed.id(), backoff(claimed.attempts()).toSeconds(), safe(e));
          count(claimed.queue(), "retry");
        }
      }
    } catch (RuntimeException e) {
      // falha ao falar com o central: o arrendamento vence e a mensagem volta sozinha
      LOG.warn(
          "Mensagem {} não pôde ser concluída agora: {}",
          claimed.id(),
          e.getClass().getSimpleName());
    } finally {
      bulkhead.release();
      total.release();
    }
  }

  private void finish(QueueRepository.Claimed claimed, String outcome, String error) {
    repository.dead(claimed.id(), error);
    count(claimed.queue(), outcome);
    LOG.warn(
        "Mensagem {} da fila {} foi para as mortas após {} tentativa(s)",
        claimed.id(),
        claimed.queue(),
        claimed.attempts());
  }

  private void count(String queue, String outcome) {
    meters.counter("liberty.queue.messages", "queue", queue, "outcome", outcome).increment();
  }

  Duration backoff(int attempts) {
    long factor = 1L << Math.min(2 * (attempts - 1), 20); // 1, 4, 16, 64...
    Duration delay = properties.baseBackoff().multipliedBy(factor);
    return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
  }

  private static String safe(RuntimeException e) {
    String message = e.getMessage();
    String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    return text.length() > 200 ? text.substring(0, 200) : text;
  }

  private String slug(long tenantId) {
    return slugs.computeIfAbsent(
        tenantId,
        id -> {
          String slug = repository.slug(id);
          if (slug == null) {
            throw new IllegalStateException("Tenant da mensagem não existe");
          }
          return slug;
        });
  }

  // ---- ciclo de vida ------------------------------------------------------------------

  @Override
  public void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "queue-dispatcher");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            poll();
          } catch (RuntimeException e) {
            LOG.warn("Rodada das filas falhou: {}", e.getClass().getSimpleName());
          }
        },
        properties.pollInterval().toMillis(),
        properties.pollInterval().toMillis(),
        TimeUnit.MILLISECONDS);
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    workers.shutdown();
    try {
      if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
        workers.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
