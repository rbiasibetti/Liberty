package br.com.liberty.engine.audit;

/**
 * Tempo gasto no banco do cliente e nos scripts durante a requisição corrente (para auditoria e
 * métricas). Vale para a thread da requisição; quem abre ({@link #begin()}) fecha ({@link #end()}).
 */
public final class RequestTimings {

  /** Totais em milissegundos. */
  public record Totals(long dbMillis, long scriptMillis) {}

  private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();

  private RequestTimings() {}

  public static void begin() {
    CURRENT.set(new long[2]);
  }

  public static Totals end() {
    long[] totals = CURRENT.get();
    CURRENT.remove();
    return totals == null
        ? new Totals(0, 0)
        : new Totals(totals[0] / 1_000_000, totals[1] / 1_000_000);
  }

  /**
   * Tempo de script acumulado até agora (nanossegundos), para separar o que roda dentro de uma
   * transação.
   */
  public static long scriptNanosNow() {
    long[] totals = CURRENT.get();
    return totals == null ? 0 : totals[1];
  }

  public static void addDb(long nanos) {
    add(0, nanos);
  }

  public static void addScript(long nanos) {
    add(1, nanos);
  }

  private static void add(int slot, long nanos) {
    long[] totals = CURRENT.get();
    if (totals != null) {
      totals[slot] += nanos;
    }
  }
}
