package br.com.liberty.engine.script;

/**
 * Captura do console dos scripts durante uma execução de sandbox (fora dela o console só vai para o
 * log de depuração). Vale para a thread que executa e é sempre fechada pelo chamador.
 */
public final class ScriptLogs implements AutoCloseable {

  private static final ThreadLocal<StringBuilder> CURRENT = new ThreadLocal<>();
  private static final int MAX_CHARS = 64 * 1024;

  private final StringBuilder buffer = new StringBuilder();

  private ScriptLogs() {}

  public static ScriptLogs capture() {
    ScriptLogs logs = new ScriptLogs();
    CURRENT.set(logs.buffer);
    return logs;
  }

  static void append(String text) {
    StringBuilder buffer = CURRENT.get();
    if (buffer != null && !text.isEmpty() && buffer.length() < MAX_CHARS) {
      buffer.append(text, 0, Math.min(text.length(), MAX_CHARS - buffer.length()));
    }
  }

  public String text() {
    return buffer.toString();
  }

  @Override
  public void close() {
    CURRENT.remove();
  }
}
