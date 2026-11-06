package br.com.liberty.poc.script;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * Executa scripts JavaScript de integradores com o isolamento descrito no ADR 0007: um {@link
 * Context} por execução, sem acesso a Java, IO, threads ou ambiente, com prazo e limite de
 * instruções. O {@link Engine} é compartilhado para reaproveitar o código compilado.
 */
public final class ScriptRunner implements AutoCloseable {

  /** Resultado de uma execução; {@code status} diz como terminou, nunca lança exceção. */
  public record Result(Status status, String json, String message, String logs, long millis) {}

  public enum Status {
    OK,
    TIMEOUT,
    STATEMENT_LIMIT,
    SCRIPT_ERROR,
    DENIED,
    BUSINESS_FAILURE,
    WORKER_FAILURE
  }

  private static final int MAX_LOG_BYTES = 64 * 1024;

  private final Engine engine =
      Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
  private final ScheduledExecutorService watchdog =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "script-watchdog");
            t.setDaemon(true);
            return t;
          });

  /** Executa {@code function(input)} do script e devolve o JSON retornado. */
  public Result run(
      String script, String function, String inputJson, Duration timeout, long maxStatements) {
    long start = System.nanoTime();
    BoundedOutput logs = new BoundedOutput(MAX_LOG_BYTES);
    ScriptApi api = new ScriptApi();
    Context context =
        Context.newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowHostClassLookup(className -> false)
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowCreateProcess(false)
            .allowNativeAccess(false)
            .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
            .allowPolyglotAccess(org.graalvm.polyglot.PolyglotAccess.NONE)
            .resourceLimits(ResourceLimits.newBuilder().statementLimit(maxStatements, null).build())
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
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      Value bindings = context.getBindings("js");
      bindings.putMember("api", api);
      context.eval("js", script);
      Value fn = bindings.getMember(function);
      Value json = context.eval("js", "JSON");
      Value input = json.invokeMember("parse", inputJson);
      Value out = fn.execute(input);
      String text = out.isNull() ? "null" : json.invokeMember("stringify", out).asString();
      return new Result(Status.OK, text, null, logs.text(), millisSince(start));
    } catch (PolyglotException e) {
      return new Result(
          classify(e, timedOut.get()), null, summary(e), logs.text(), millisSince(start));
    } finally {
      timer.cancel(false);
      context.close(true);
    }
  }

  private static Status classify(PolyglotException e, boolean timedOut) {
    if (String.valueOf(e.getMessage()).contains("Statement count limit")) {
      return Status.STATEMENT_LIMIT;
    }
    if (timedOut || e.isCancelled()) {
      return Status.TIMEOUT;
    }
    if (e.isHostException()) {
      Throwable host = e.asHostException();
      return host instanceof ScriptApi.BusinessFailure
          ? Status.BUSINESS_FAILURE
          : Status.SCRIPT_ERROR;
    }
    String message = String.valueOf(e.getMessage());
    if (message.contains("not allowed")
        || message.contains("Access to")
        || message.contains("denied")) {
      return Status.DENIED;
    }
    return Status.SCRIPT_ERROR;
  }

  private static String summary(PolyglotException e) {
    String message = String.valueOf(e.getMessage());
    return message.length() > 300 ? message.substring(0, 300) : message;
  }

  private static long millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  @Override
  public void close() {
    watchdog.shutdownNow();
    engine.close(true);
  }

  /** Saída limitada: descarta o excesso em vez de crescer sem limite. */
  private static final class BoundedOutput extends OutputStream {
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
      return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
  }
}
