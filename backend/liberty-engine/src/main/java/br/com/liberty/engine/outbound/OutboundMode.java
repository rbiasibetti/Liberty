package br.com.liberty.engine.outbound;

/**
 * Marca a thread corrente como "sandbox": o {@code http.fetch} valida tudo mas não envia nada e
 * devolve uma resposta simulada. Sempre fechado pelo chamador.
 */
public final class OutboundMode implements AutoCloseable {

  private static final ThreadLocal<Boolean> SIMULATE = new ThreadLocal<>();

  private OutboundMode() {}

  public static OutboundMode simulate() {
    SIMULATE.set(Boolean.TRUE);
    return new OutboundMode();
  }

  public static boolean simulated() {
    return Boolean.TRUE.equals(SIMULATE.get());
  }

  @Override
  public void close() {
    SIMULATE.remove();
  }
}
