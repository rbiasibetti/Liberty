package br.com.liberty.engine.exports;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.queue.QueueEnvelope;
import br.com.liberty.engine.queue.QueueHandler;
import br.com.liberty.engine.queue.QueueProperties;
import br.com.liberty.engine.read.ReadEngine;
import br.com.liberty.engine.route.PublishedRoutes;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fila {@code export}: gera o CSV de uma integração de leitura publicada, página a página pelo
 * próprio motor de leitura (mesmas validações, mesmo banco do tenant), e guarda no armazenamento.
 * Idempotente: pedido já concluído ou falho é ignorado. A mensagem de erro gravada no pedido é
 * genérica; o detalhe técnico fica no log.
 */
public final class ExportHandler implements QueueHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ExportHandler.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final int MAX_OFFSET = 100_000; // limite do motor de leitura

  private final ExportRepository repository;
  private final ObjectStore store;
  private final PublishedRoutes routes;
  private final ReadEngine reads;
  private final ExportProperties properties;
  private final QueueProperties queueProperties;
  private final Clock clock;

  public ExportHandler(
      ExportRepository repository,
      ObjectStore store,
      PublishedRoutes routes,
      ReadEngine reads,
      ExportProperties properties,
      QueueProperties queueProperties,
      Clock clock) {
    this.repository = repository;
    this.store = store;
    this.routes = routes;
    this.reads = reads;
    this.properties = properties;
    this.queueProperties = queueProperties;
    this.clock = clock;
  }

  @Override
  public String queue() {
    return "export";
  }

  @Override
  public void handle(QueueEnvelope message) {
    long jobId = message.payload().path("jobId").asLong();
    Long tenantId = repository.tenantId(message.tenant());
    if (tenantId == null) {
      throw new Permanent("Tenant inexistente");
    }
    ExportRepository.Job job =
        repository
            .find(tenantId, jobId)
            .orElseThrow(() -> new Permanent("Pedido de exportação inexistente"));
    if (!job.status().equals("PENDING") && !job.status().equals("PROCESSING")) {
      return;
    }
    repository.processing(tenantId, jobId);
    try {
      generate(job, tenantId, message.tenant());
    } catch (Refused e) {
      repository.failed(tenantId, jobId, e.getMessage());
      throw new Permanent(e.getMessage());
    } catch (RuntimeException | IOException e) {
      LOG.warn(
          "Exportação {} falhou na tentativa {}: {}",
          jobId,
          message.attempt(),
          e.getClass().getSimpleName());
      if (message.attempt() >= queueProperties.maxAttempts()) {
        repository.failed(tenantId, jobId, "Falha ao gerar o arquivo");
        throw new Permanent("Falha ao gerar o arquivo");
      }
      throw new IllegalStateException("Falha ao gerar o arquivo");
    }
  }

  /** Recusa definitiva, com texto seguro para o usuário. */
  private static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message);
    }
  }

  private void generate(ExportRepository.Job job, long tenantId, String slug) throws IOException {
    JsonNode filter = MAPPER.readTree(job.filterJson());
    var environment = EnvironmentKind.valueOf(filter.path("environment").asString());
    TenantScope scope = new TenantScope(new TenantId(slug), environment);
    var integration =
        routes
            .find(scope, filter.path("integration").asString())
            .orElseThrow(() -> new Refused("A integração não está mais publicada neste ambiente"));
    var select = integration.snapshot().operation().select();
    if (select == null) {
      throw new Refused("A integração não é de leitura");
    }
    Map<String, String> pathVariables = new LinkedHashMap<>();
    Map<String, List<String>> query = new LinkedHashMap<>();
    Map<String, String> params = ExportService.paramsOf(job.filterJson());
    for (var declared : integration.snapshot().request().params()) {
      String value = params.get(declared.name());
      if (value == null) {
        continue;
      }
      if ("path".equals(declared.in())) {
        pathVariables.put(declared.name(), value);
      } else {
        query.put(declared.name(), List.of(value));
      }
    }
    int configuredMax =
        select.pageSize() == null || select.pageSize().max() == null
            ? properties.pageSize()
            : select.pageSize().max();
    int pageSize = Math.max(1, Math.min(properties.pageSize(), configuredMax));
    String key = tenantId + "/" + job.id() + ".csv";
    List<String> columns = new ArrayList<>(select.columns());
    long rows = 0;
    try (ObjectStore.Upload upload = store.begin(key);
        CsvWriter csv = new CsvWriter(upload.stream(), properties.maxBytes())) {
      boolean headerWritten = false;
      int offset = 0;
      boolean more = true;
      while (more) {
        query.put("limit", List.of(Integer.toString(pageSize)));
        query.put("offset", List.of(Integer.toString(offset)));
        Object body;
        try {
          body = reads.execute(scope, integration, pathVariables, query).body();
        } catch (ProblemException e) {
          if (e.status() >= 400 && e.status() < 500) {
            throw new Refused(
                e.status() == 404
                    ? "Nenhum registro corresponde aos filtros"
                    : "Parâmetros inválidos para a integração");
          }
          throw e;
        }
        List<Map<String, Object>> items = itemsOf(body);
        if (columns.isEmpty() && !items.isEmpty()) {
          columns.addAll(items.getFirst().keySet());
        }
        if (!headerWritten && !columns.isEmpty()) {
          csv.header(columns);
          headerWritten = true;
        }
        for (Map<String, Object> item : items) {
          if (++rows > properties.maxRows()) {
            throw new Refused(
                "A exportação passa do limite de "
                    + properties.maxRows()
                    + " linhas; refine os filtros");
          }
          csv.row(item, columns);
        }
        more = isPage(body) && hasMore(body);
        offset += pageSize;
        if (more && offset > MAX_OFFSET) {
          throw new Refused("A exportação passa do limite de linhas; refine os filtros");
        }
      }
      csv.close();
      upload.commit();
    } catch (CsvWriter.TooLarge e) {
      throw new Refused("O arquivo passaria do limite de tamanho; refine os filtros");
    }
    repository.completed(tenantId, job.id(), key, clock.instant().plus(properties.retention()));
    LOG.info("Exportação {} de {} concluída ({} linhas)", job.id(), slug, rows);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> itemsOf(Object body) {
    if (isPage(body)) {
      return (List<Map<String, Object>>) ((Map<String, Object>) body).get("items");
    }
    return List.of((Map<String, Object>) body);
  }

  @SuppressWarnings("unchecked")
  private static boolean isPage(Object body) {
    return body instanceof Map<?, ?> map && map.containsKey("items") && map.containsKey("page");
  }

  @SuppressWarnings("unchecked")
  private static boolean hasMore(Object body) {
    return Boolean.TRUE.equals(
        ((Map<String, Object>) ((Map<String, Object>) body).get("page")).get("hasMore"));
  }
}
