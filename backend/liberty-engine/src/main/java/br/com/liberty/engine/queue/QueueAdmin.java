package br.com.liberty.engine.queue;

import br.com.liberty.engine.problem.ProblemException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visão e tratamento das filas de um tenant. Toda consulta e toda ação filtram pelo tenant; uma
 * mensagem de outro tenant é "não encontrada". O conteúdo das mensagens não é exposto.
 */
public class QueueAdmin {

  private static final Logger LOG = LoggerFactory.getLogger(QueueAdmin.class);
  private static final int DEAD_LIMIT = 200;

  private final QueueRepository repository;

  public QueueAdmin(QueueRepository repository) {
    this.repository = repository;
  }

  public List<QueueRepository.Stat> stats(String slug) {
    return repository.stats(tenant(slug));
  }

  public List<QueueRepository.Dead> dead(String slug) {
    return repository.dead(tenant(slug), DEAD_LIMIT);
  }

  public void requeue(String slug, long id, String user) {
    if (!repository.requeue(tenant(slug), id)) {
      throw notFound();
    }
    LOG.info("Mensagem {} de {} devolvida à fila por {}", id, slug, user);
  }

  public void discard(String slug, long id, String user) {
    if (!repository.discard(tenant(slug), id)) {
      throw notFound();
    }
    LOG.info("Mensagem morta {} de {} descartada por {}", id, slug, user);
  }

  private long tenant(String slug) {
    Long id = repository.tenantId(slug);
    if (id == null) {
      throw notFound();
    }
    return id;
  }

  private static ProblemException notFound() {
    return new ProblemException(404, "not-found", "Não encontrado", "Mensagem não encontrada");
  }
}
