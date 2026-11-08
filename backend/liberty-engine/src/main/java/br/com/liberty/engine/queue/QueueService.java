package br.com.liberty.engine.queue;

import br.com.liberty.domain.tenancy.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publicação em filas, vinda dos scripts. A mensagem só é gravada depois que a escrita do tenant
 * confirmou (commit): durante o script ela fica numa lista da thread e {@link #flush} grava; se a
 * transação desfaz, se tenta de novo por impasse ou se é sandbox, nada é publicado. Como o banco do
 * tenant não é o central, não há atomicidade entre os dois: uma queda entre o commit e a gravação
 * perde a mensagem (no máximo uma vez na publicação, pelo menos uma vez na entrega).
 */
public class QueueService {

  private static final Logger LOG = LoggerFactory.getLogger(QueueService.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Recusa com texto seguro para o script ver. */
  public static final class Rejected extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    Rejected(String message) {
      super(message);
    }
  }

  private record Pending(String queue, String payloadJson) {}

  private static final ThreadLocal<List<Pending>> OUTBOX = new ThreadLocal<>();

  private final QueueRepository repository;
  private final QueueProperties properties;
  private final Set<String> queues;
  private final MeterRegistry meters;
  private final Map<String, Long> tenantIds = new ConcurrentHashMap<>();

  public QueueService(
      QueueRepository repository,
      QueueProperties properties,
      List<QueueHandler> handlers,
      MeterRegistry meters) {
    this.repository = repository;
    this.properties = properties;
    this.queues = Set.copyOf(handlers.stream().map(QueueHandler::queue).toList());
    this.meters = meters;
  }

  /** Sessão de uma execução de script. */
  public final class Session {
    private final TenantScope scope;
    private final boolean allowed;
    private final boolean simulate;
    private int published;

    private Session(TenantScope scope, boolean allowed, boolean simulate) {
      this.scope = scope;
      this.allowed = allowed;
      this.simulate = simulate;
    }

    /** Devolve {@code {queued, simulated}} em JSON. */
    public String publish(String queue, String payloadJson) {
      if (!allowed) {
        throw new Rejected("queue.publish só pode ser chamado em afterExecute, depois da gravação");
      }
      if (!queues.contains(queue)) {
        throw new Rejected("Fila desconhecida");
      }
      if (++published > properties.maxPublishPerExecution()) {
        throw new Rejected("Limite de mensagens por execução excedido");
      }
      JsonNode payload;
      try {
        payload = MAPPER.readTree(payloadJson);
      } catch (RuntimeException e) {
        throw new Rejected("Conteúdo da mensagem inválido");
      }
      if (payload == null || !payload.isObject()) {
        throw new Rejected("O conteúdo da mensagem deve ser um objeto");
      }
      String text = MAPPER.writeValueAsString(payload);
      if (text.getBytes(StandardCharsets.UTF_8).length > properties.maxPayloadBytes()) {
        throw new Rejected("Mensagem grande demais");
      }
      if (simulate) {
        return "{\"queued\":false,\"simulated\":true}";
      }
      if (repository.pending(tenantId(scope.tenant().value()))
          >= properties.maxPendingPerTenant()) {
        throw new Rejected("Fila do tenant cheia");
      }
      List<Pending> outbox = OUTBOX.get();
      if (outbox == null) {
        throw new Rejected("queue.publish indisponível neste contexto");
      }
      outbox.add(new Pending(queue, text));
      return "{\"queued\":true,\"simulated\":false}";
    }
  }

  public Session session(TenantScope scope, boolean allowed, boolean simulate) {
    return new Session(scope, allowed, simulate);
  }

  /**
   * Enfileira direto, sem esperar commit: para o motor (exportações), não para scripts. Respeita o
   * teto de pendentes do tenant.
   */
  public void enqueue(TenantScope scope, String queue, String payloadJson) {
    if (!queues.contains(queue)) {
      throw new Rejected("Fila desconhecida");
    }
    long tenantId = tenantId(scope.tenant().value());
    if (repository.pending(tenantId) >= properties.maxPendingPerTenant()) {
      throw new Rejected("Fila do tenant cheia");
    }
    repository.enqueue(
        tenantId, scope.environment().name(), queue, payloadJson, MDC.get("requestId"));
    meters.counter("liberty.queue.published", "queue", queue).increment();
  }

  /** Começa uma tentativa de execução: descarta o que uma tentativa anterior deixou. */
  public static void begin() {
    OUTBOX.set(new ArrayList<>());
  }

  /** Termina a tentativa sem publicar nada que ainda esteja pendente. */
  public static void end() {
    OUTBOX.remove();
  }

  /**
   * Grava o que os scripts publicaram; só chamar depois do commit. Falhas são registradas, nunca
   * lançadas.
   */
  public void flush(TenantScope scope) {
    List<Pending> pending = OUTBOX.get();
    if (pending == null || pending.isEmpty()) {
      return;
    }
    OUTBOX.set(new ArrayList<>());
    try {
      long tenantId = tenantId(scope.tenant().value());
      String correlation = MDC.get("requestId");
      for (Pending message : pending) {
        repository.enqueue(
            tenantId,
            scope.environment().name(),
            message.queue(),
            message.payloadJson(),
            correlation);
        meters.counter("liberty.queue.published", "queue", message.queue()).increment();
      }
    } catch (RuntimeException e) {
      meters.counter("liberty.queue.publish.failed").increment(pending.size());
      LOG.error(
          "Mensagens de {} não foram gravadas depois do commit ({} perdidas): {}",
          scope.tenant().value(),
          pending.size(),
          e.getClass().getSimpleName());
    }
  }

  long tenantId(String slug) {
    return tenantIds.computeIfAbsent(
        slug,
        s -> {
          Long id = repository.tenantId(s);
          if (id == null) {
            throw new Rejected("Tenant desconhecido");
          }
          return id;
        });
  }
}
