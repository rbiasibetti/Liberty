package br.com.liberty.engine.write;

import br.com.liberty.domain.tenancy.TenantScope;

/**
 * Idempotência de escritas: a chave é reservada antes de gravar e concluída (com a resposta)
 * depois; se a gravação não acontecer, a reserva é desfeita.
 */
public interface IdempotencyService {

  /**
   * O que fazer com a requisição: executar (e depois concluir ou liberar) ou repetir a resposta.
   */
  sealed interface Decision permits Proceed, Replay {}

  /** Reserva feita; {@code token} é de uso exclusivo da implementação. */
  record Proceed(Object token) implements Decision {}

  record Replay(int status, String body) implements Decision {}

  /**
   * @throws br.com.liberty.engine.problem.ProblemException 409 se a chave foi usada com outra
   *     requisição ou ainda está em andamento
   */
  Decision begin(TenantScope scope, long integrationId, String key, byte[] requestHash);

  void complete(Proceed reservation, int status, String body);

  void release(Proceed reservation);
}
