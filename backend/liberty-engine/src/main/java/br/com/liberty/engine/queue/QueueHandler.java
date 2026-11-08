package br.com.liberty.engine.queue;

/**
 * Consumidor de uma fila. A entrega é pelo menos uma vez (uma mensagem pode chegar de novo se o
 * consumidor morrer no meio): o tratamento precisa ser idempotente. Lançar {@link Permanent} manda
 * a mensagem direto para a fila de mortas; qualquer outra exceção agenda nova tentativa com recuo,
 * e esgotadas as tentativas ela também vai para as mortas.
 */
public interface QueueHandler {

  /** Nome da fila que este consumidor atende. */
  String queue();

  void handle(QueueEnvelope message);

  /** Falha sem sentido tentar de novo (mensagem malformada, destino recusado). */
  final class Permanent extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Permanent(String message) {
      super(message);
    }
  }
}
