package br.com.liberty.scripts.worker;

import br.com.liberty.scripts.ScriptExecutor.Outcome;
import br.com.liberty.scripts.ScriptExecutor.Request;
import br.com.liberty.scripts.ScriptExecutor.Status;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Executa um script num {@link Context} novo por execução: sem Java, arquivos, rede, threads,
 * processos nem ambiente; só a API {@code db}/{@code fail} injetada pelo pai. Prazo por
 * cancelamento e limite de instruções; saída e resultado limitados.
 */
final class Sandbox implements AutoCloseable {

  static final int MAX_LOG_BYTES = 64 * 1024;
  static final int MAX_RESULT_CHARS = 1024 * 1024;

  private static final String PRELUDE =
      """
      (function (h) {
        const call = (fn) => { try { return fn(); } catch (e) { throw new Error(String(e.message || e)); } };
        globalThis.db = Object.freeze({
          named: (name, params) => JSON.parse(call(() => h.named(String(name), JSON.stringify(params || {})))),
          lookup: (table, keyColumn, key, valueColumn) =>
            JSON.parse(call(() => h.lookup(String(table), String(keyColumn), JSON.stringify(key), String(valueColumn))))
        });
        globalThis.fail = (status, message) => h.fail(status | 0, String(message));
      })(__host);
      delete globalThis.__host;
      """;

  private final Engine engine =
      Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
  private final ScheduledExecutorService watchdog =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "script-watchdog");
            t.setDaemon(true);
            return t;
          });

  Outcome run(Request request, HostBridge bridge) {
    long start = System.nanoTime();
    BoundedOutput logs = new BoundedOutput(MAX_LOG_BYTES);
    Context context =
        Context.newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowHostClassLookup(className -> false)
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowNativeAccess(false)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .resourceLimits(
                ResourceLimits.newBuilder().statementLimit(request.maxStatements(), null).build())
            .out(logs)
            .err(logs)
            .build();
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> timer =
        watchdog.schedule(
            () -> {
              timedOut.set(true);
              context.close(true);
            },
            request.timeout().toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      Value bindings = context.getBindings("js");
      bindings.putMember("__host", bridge);
      context.eval("js", PRELUDE);
      context.eval("js", request.script());
      Value fn = bindings.getMember(request.function());
      if (fn == null || !fn.canExecute()) {
        return outcome(
            Status.SCRIPT_ERROR,
            null,
            "Função não definida: " + request.function(),
            0,
            logs,
            start);
      }
      Value json = context.eval("js", "JSON");
      Value input = json.invokeMember("parse", request.inputJson());
      Value result = fn.execute(input);
      String text = result.isNull() ? "null" : json.invokeMember("stringify", result).asString();
      if (text == null || text.length() > MAX_RESULT_CHARS) {
        return outcome(
            Status.SCRIPT_ERROR, null, "Resultado do script grande demais", 0, logs, start);
      }
      return outcome(Status.OK, text, null, 0, logs, start);
    } catch (PolyglotException e) {
      return classify(e, timedOut.get(), logs, start);
    } finally {
      timer.cancel(false);
      context.close(true);
    }
  }

  private static Outcome classify(
      PolyglotException e, boolean timedOut, BoundedOutput logs, long start) {
    String message = String.valueOf(e.getMessage());
    if (message.contains("Statement count limit") || timedOut || e.isCancelled()) {
      return outcome(
          Status.TIMEOUT, null, "Prazo ou limite de instruções excedido", 0, logs, start);
    }
    if (e.isHostException() && e.asHostException() instanceof HostBridge.BusinessFailure failure) {
      return outcome(
          Status.BUSINESS_FAILURE, null, failure.getMessage(), failure.status(), logs, start);
    }
    String summary = message.length() > 300 ? message.substring(0, 300) : message;
    return outcome(Status.SCRIPT_ERROR, null, summary, 0, logs, start);
  }

  private static Outcome outcome(
      Status status, String json, String message, int http, BoundedOutput logs, long start) {
    return new Outcome(
        status, json, message, http, logs.text(), (System.nanoTime() - start) / 1_000_000);
  }

  @Override
  public void close() {
    watchdog.shutdownNow();
    engine.close(true);
  }

  /** Saída limitada: descarta o excesso em vez de crescer sem limite. */
  static final class BoundedOutput extends OutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final int limit;

    BoundedOutput(int limit) {
      this.limit = limit;
    }

    @Override
    public synchronized void write(int b) {
      if (buffer.size() < limit) {
        buffer.write(b);
      }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      int room = limit - buffer.size();
      if (room > 0) {
        buffer.write(bytes, offset, Math.min(room, length));
      }
    }

    synchronized String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }
}
