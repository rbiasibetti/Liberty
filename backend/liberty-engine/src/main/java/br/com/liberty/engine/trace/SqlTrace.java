package br.com.liberty.engine.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rastro dos comandos SQL de uma execução de sandbox: texto com marcadores (nunca os valores, que
 * podem ser dados pessoais), linhas afetadas ou devolvidas e duração. Limitado para não crescer sem
 * fim.
 */
public final class SqlTrace {

  /** Um comando executado; {@code rows} é -1 quando o driver não informou. */
  public record Entry(String sql, int rows, long millis) {}

  public static final int MAX_ENTRIES = 200;

  private final List<Entry> entries = new ArrayList<>();
  private boolean truncated;

  public synchronized void record(String sql, int rows, long millis) {
    if (entries.size() < MAX_ENTRIES) {
      entries.add(new Entry(sql, rows, millis));
    } else {
      truncated = true;
    }
  }

  public synchronized List<Entry> entries() {
    return Collections.unmodifiableList(new ArrayList<>(entries));
  }

  public synchronized boolean truncated() {
    return truncated;
  }
}
