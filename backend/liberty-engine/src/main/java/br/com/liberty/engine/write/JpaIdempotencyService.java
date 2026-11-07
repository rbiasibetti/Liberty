package br.com.liberty.engine.write;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.infrastructure.persistence.IdempotencyEntity;
import br.com.liberty.infrastructure.persistence.IdempotencyRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotência de escritas. A chave é reservada (IN_PROGRESS) antes de gravar e concluída (DONE,
 * com a resposta) depois; se a gravação falhar, a reserva é desfeita para a repetição poder tentar
 * de novo. Uma reserva IN_PROGRESS nunca é tomada de assalto: se o processo cair no meio, a chave
 * fica bloqueada até expirar, o que é preferível a gravar duas vezes. Os registros ficam no banco
 * do core (não no do tenant), então a conclusão não é atômica com a gravação; veja o ADR 0025.
 */
public class JpaIdempotencyService implements IdempotencyService {

  private static final Logger LOG = LoggerFactory.getLogger(JpaIdempotencyService.class);

  private final IdempotencyRepository repository;
  private final TenantRepository tenants;
  private final TransactionTemplate transactions;
  private final Duration ttl;

  public JpaIdempotencyService(
      IdempotencyRepository repository,
      TenantRepository tenants,
      TransactionTemplate transactions,
      Duration ttl) {
    this.repository = repository;
    this.tenants = tenants;
    this.transactions = transactions;
    this.ttl = ttl;
  }

  @Override
  public Decision begin(TenantScope scope, long integrationId, String key, byte[] requestHash) {
    long tenantId =
        tenants
            .findBySlug(scope.tenant().value())
            .orElseThrow(() -> new IllegalStateException("Tenant inexistente"))
            .getId();
    var id = new IdempotencyEntity.Key(tenantId, integrationId, key);
    for (int attempt = 0; attempt < 3; attempt++) {
      Optional<IdempotencyEntity> found = transactions.execute(status -> repository.findById(id));
      if (found.isPresent()) {
        IdempotencyEntity entry = found.get();
        if (entry.getExpiresAt().isBefore(Instant.now())) {
          transactions.executeWithoutResult(status -> repository.deleteById(id));
          continue;
        }
        return existing(entry, id, requestHash);
      }
      try {
        transactions.executeWithoutResult(
            status ->
                repository.save(new IdempotencyEntity(id, requestHash, Instant.now().plus(ttl))));
        return new Proceed(id);
      } catch (DataIntegrityViolationException raced) {
        // outra requisição reservou no meio: relê e decide pelo que ela gravou
      }
    }
    throw new ProblemException(
        409,
        "idempotency-in-progress",
        "Requisição em andamento",
        "Outra requisição com esta chave está em andamento",
        List.of(),
        Map.of("Retry-After", "1"));
  }

  private static Decision existing(IdempotencyEntity entry, IdempotencyEntity.Key id, byte[] hash) {
    if (!MessageDigest.isEqual(entry.getRequestSha256(), hash)) {
      throw new ProblemException(
          409,
          "idempotency-key-reuse",
          "Chave de idempotência já usada",
          "Esta chave de idempotência foi usada com outra requisição");
    }
    if ("DONE".equals(entry.getStatus())) {
      return new Replay(entry.getHttpStatus(), entry.getResponse());
    }
    throw new ProblemException(
        409,
        "idempotency-in-progress",
        "Requisição em andamento",
        "Outra requisição com esta chave está em andamento",
        List.of(),
        Map.of("Retry-After", "1"));
  }

  /** Grava a resposta; uma falha aqui não desfaz a escrita (já confirmada) e só é registrada. */
  @Override
  public void complete(Proceed reservation, int status, String body) {
    try {
      transactions.executeWithoutResult(
          s ->
              repository
                  .findById((IdempotencyEntity.Key) reservation.token())
                  .ifPresent(
                      entry -> {
                        entry.complete((short) status, body);
                        repository.save(entry);
                      }));
    } catch (RuntimeException e) {
      LOG.error("Escrita concluída, mas a resposta da chave de idempotência não foi gravada", e);
    }
  }

  /** Desfaz a reserva de uma escrita que não aconteceu. */
  @Override
  public void release(Proceed reservation) {
    try {
      transactions.executeWithoutResult(
          s -> repository.deleteById((IdempotencyEntity.Key) reservation.token()));
    } catch (RuntimeException e) {
      LOG.error("Não foi possível liberar a reserva da chave de idempotência", e);
    }
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
  void purgeExpired() {
    try {
      long removed = transactions.execute(s -> repository.deleteByExpiresAtBefore(Instant.now()));
      if (removed > 0) {
        LOG.info("Removidos {} registros de idempotência expirados", removed);
      }
    } catch (RuntimeException e) {
      LOG.warn("Falha ao limpar registros de idempotência expirados", e);
    }
  }
}
