package br.com.liberty.engine.exports;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.engine.queue.QueueService;
import br.com.liberty.engine.route.PublishedRoutes;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exportações: pedido, acompanhamento e download por URL assinada. O arquivo é gerado fora da
 * requisição, pela fila {@code export}. Todo acesso é por tenant: o pedido de outro tenant é "não
 * encontrado" e um link só vale para o tenant e o pedido que a assinatura nomeia.
 */
public class ExportService {

  private static final Logger LOG = LoggerFactory.getLogger(ExportService.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Pedido de exportação de uma integração de leitura publicada. */
  public record Request(
      String integration, String environment, Map<String, String> params, String format) {}

  /** Estado de um pedido. */
  public record View(
      long id,
      String status,
      String format,
      String integration,
      String environment,
      String requestedBy,
      String error,
      Instant createdAt,
      Instant expiresAt) {}

  /** URL assinada e quando ela vence. */
  public record Link(String url, Instant expiresAt) {}

  /** Arquivo pronto para entregar. */
  public record Download(String filename, String contentType, InputStream stream) {}

  private final ExportRepository repository;
  private final ObjectStore store;
  private final ExportLinks links;
  private final PublishedRoutes routes;
  private final QueueService queues;
  private final ExportProperties properties;
  private final Clock clock;

  public ExportService(
      ExportRepository repository,
      ObjectStore store,
      ExportLinks links,
      PublishedRoutes routes,
      QueueService queues,
      ExportProperties properties,
      Clock clock) {
    this.repository = repository;
    this.store = store;
    this.links = links;
    this.routes = routes;
    this.queues = queues;
    this.properties = properties;
    this.clock = clock;
  }

  public View request(String slug, String user, Request request) {
    long tenantId = tenant(slug);
    List<Violation> problems = new java.util.ArrayList<>();
    EnvironmentKind environment = null;
    try {
      environment =
          EnvironmentKind.valueOf(String.valueOf(request.environment()).toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      problems.add(new Violation("environment", "invalid_value", "Use HML ou PRD"));
    }
    String format = request.format() == null ? "CSV" : request.format().toUpperCase(Locale.ROOT);
    if (!format.equals("CSV")) {
      problems.add(
          new Violation(
              "format",
              "unsupported",
              format.equals("XLSX") ? "XLSX ainda não é suportado; use CSV" : "Use CSV"));
    }
    Map<String, String> params = request.params() == null ? Map.of() : request.params();
    if (params.size() > 20
        || params.values().stream().anyMatch(v -> v == null || v.length() > 500)) {
      problems.add(
          new Violation(
              "params", "invalid_value", "No máximo 20 parâmetros de até 500 caracteres"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    TenantScope scope = new TenantScope(new TenantId(slug), environment);
    var integration =
        routes
            .find(scope, String.valueOf(request.integration()))
            .orElseThrow(
                () ->
                    new ProblemException(
                        404,
                        "not-found",
                        "Não encontrado",
                        "Integração não publicada neste ambiente"));
    if (!"SELECT".equals(integration.snapshot().operation().type())) {
      throw ProblemException.validation(
          List.of(
              new Violation(
                  "integration", "invalid_value", "Só integrações de leitura são exportáveis")));
    }
    var declared = integration.snapshot().request().params().stream().map(p -> p.name()).toList();
    for (String name : params.keySet()) {
      if (!declared.contains(name)) {
        throw ProblemException.validation(
            List.of(
                new Violation(
                    "params." + name, "unknown_param", "Parâmetro não declarado pela integração")));
      }
    }
    if (repository.active(tenantId) >= properties.maxActivePerTenant()) {
      throw new ProblemException(
          429,
          "too-many-requests",
          "Muitas exportações",
          "Já há exportações em andamento; aguarde o término",
          List.of(),
          Map.of("Retry-After", "30"));
    }
    ObjectNode filter = MAPPER.createObjectNode();
    filter.put("integration", integration.key()).put("environment", environment.name());
    ObjectNode values = filter.putObject("params");
    new java.util.TreeMap<>(params).forEach(values::put);
    long id = repository.create(tenantId, user, format, MAPPER.writeValueAsString(filter));
    try {
      queues.enqueue(scope, "export", "{\"jobId\":" + id + "}");
    } catch (RuntimeException e) {
      repository.failed(tenantId, id, "Não foi possível enfileirar");
      throw new ProblemException(
          503,
          "queue-unavailable",
          "Serviço indisponível",
          "Não foi possível enfileirar a exportação agora");
    }
    LOG.info("Exportação {} de {} pedida por {} ({})", id, slug, user, integration.key());
    return get(slug, id);
  }

  public List<View> list(String slug) {
    return repository.recent(tenant(slug), 50).stream().map(ExportService::view).toList();
  }

  public View get(String slug, long id) {
    return view(job(tenant(slug), id));
  }

  /** URL assinada para baixar um arquivo pronto. */
  public Link link(String slug, long id, String user) {
    ExportRepository.Job job = job(tenant(slug), id);
    ready(job);
    Instant expires = clock.instant().plus(properties.linkTtl());
    LOG.info("Link de download da exportação {} de {} gerado por {}", id, slug, user);
    return new Link("/api/v1/exports/download?token=" + links.sign(slug, id, expires), expires);
  }

  /** Download pelo token assinado; sem login, o token é a autorização. */
  public Download download(String token) {
    var claims =
        links
            .verify(token)
            .orElseThrow(
                () ->
                    new ProblemException(
                        403, "invalid-link", "Link inválido", "O link é inválido ou venceu"));
    long tenantId = tenant(claims.tenant());
    ExportRepository.Job job = job(tenantId, claims.jobId());
    ready(job);
    try {
      return new Download(
          "exportacao-" + job.id() + ".csv",
          "text/csv; charset=utf-8",
          store.open(job.objectKey()));
    } catch (IOException e) {
      LOG.error(
          "Arquivo da exportação {} não pôde ser aberto: {}",
          job.id(),
          e.getClass().getSimpleName());
      throw new ProblemException(
          503,
          "storage-unavailable",
          "Serviço indisponível",
          "O arquivo não está disponível agora");
    }
  }

  /** Remove os arquivos vencidos. */
  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
  public void purgeExpired() {
    for (ExportRepository.Job job : repository.expired(200)) {
      try {
        store.delete(job.objectKey());
        repository.cleared(job.id());
      } catch (IOException | RuntimeException e) {
        LOG.warn(
            "Arquivo vencido da exportação {} não foi removido: {}",
            job.id(),
            e.getClass().getSimpleName());
      }
    }
  }

  private void ready(ExportRepository.Job job) {
    if (!"COMPLETED".equals(job.status())) {
      throw new ProblemException(
          409,
          "not-ready",
          "Exportação não está pronta",
          "O arquivo ainda não foi gerado ou a geração falhou");
    }
    if (job.objectKey() == null
        || job.expiresAt() == null
        || !job.expiresAt().isAfter(clock.instant())) {
      throw new ProblemException(
          410, "expired", "Arquivo expirado", "O arquivo desta exportação expirou; peça de novo");
    }
  }

  private long tenant(String slug) {
    Long id = repository.tenantId(slug);
    if (id == null) {
      throw notFound();
    }
    return id;
  }

  private ExportRepository.Job job(long tenantId, long id) {
    return repository.find(tenantId, id).orElseThrow(ExportService::notFound);
  }

  private static ProblemException notFound() {
    return new ProblemException(404, "not-found", "Não encontrado", "Exportação não encontrada");
  }

  private static View view(ExportRepository.Job job) {
    var filter = MAPPER.readTree(job.filterJson());
    return new View(
        job.id(),
        job.status(),
        job.format(),
        filter.path("integration").asString(""),
        filter.path("environment").asString(""),
        job.requestedBy(),
        job.error(),
        job.createdAt(),
        job.expiresAt());
  }

  /** Para os consumidores. */
  static Map<String, String> paramsOf(String filterJson) {
    Map<String, String> params = new LinkedHashMap<>();
    MAPPER
        .readTree(filterJson)
        .path("params")
        .properties()
        .forEach(e -> params.put(e.getKey(), e.getValue().asString("")));
    return params;
  }
}
