package br.com.liberty.poc.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pool de processos JVM trabalhadores, cada um com heap limitado. Um script hostil só derruba o
 * trabalhador que o executa; o pai mata (SIGKILL) quem passar do prazo e repõe o pool.
 */
public final class ProcessScriptPool implements AutoCloseable {

  private static final long START_TIMEOUT_MILLIS = 20_000;

  private final BlockingQueue<Worker> idle;
  private final List<Worker> all = new ArrayList<>();
  private final String heap;
  private final AtomicLong ids = new AtomicLong();
  private final long killGraceMillis;

  public ProcessScriptPool(int size, String heap, Duration killGrace) throws IOException {
    this.idle = new ArrayBlockingQueue<>(size);
    this.heap = heap;
    this.killGraceMillis = killGrace.toMillis();
    for (int i = 0; i < size; i++) {
      Worker w = new Worker(start());
      w.awaitReady();
      all.add(w);
      idle.add(w);
    }
  }

  public ScriptRunner.Result run(
      String script, String function, String inputJson, Duration timeout, long maxStatements)
      throws InterruptedException, IOException {
    return run(
        script, function, inputJson, timeout, maxStatements, timeout.plusMillis(killGraceMillis));
  }

  /** Prazo interno do trabalhador ({@code timeout}) e prazo duro do pai ({@code hardDeadline}). */
  public ScriptRunner.Result run(
      String script,
      String function,
      String inputJson,
      Duration timeout,
      long maxStatements,
      Duration hardDeadline)
      throws InterruptedException, IOException {
    Worker worker = idle.take();
    long start = System.nanoTime();
    try {
      long id = ids.incrementAndGet();
      worker.send(
          id
              + "\t"
              + WorkerMain.encode(script)
              + "\t"
              + function
              + "\t"
              + WorkerMain.encode(inputJson)
              + "\t"
              + timeout.toMillis()
              + "\t"
              + maxStatements);
      String reply = readReply(worker, hardDeadline);
      String[] f = reply.split("\t", -1);
      String body = WorkerMain.decode(f[2]);
      var status = ScriptRunner.Status.valueOf(f[1]);
      return new ScriptRunner.Result(
          status,
          status == ScriptRunner.Status.OK ? body : null,
          status == ScriptRunner.Status.OK ? null : body,
          "",
          Long.parseLong(f[3]));
    } catch (IOException | RuntimeException e) {
      worker.kill();
      Worker replacement = new Worker(start());
      replacement.awaitReady();
      synchronized (all) {
        all.set(all.indexOf(worker), replacement);
      }
      worker = replacement;
      return new ScriptRunner.Result(
          ScriptRunner.Status.WORKER_FAILURE,
          null,
          e.getClass().getSimpleName(),
          "",
          (System.nanoTime() - start) / 1_000_000);
    } finally {
      idle.add(worker);
    }
  }

  private static String readReply(Worker worker, Duration deadline) throws IOException {
    try {
      return worker.readLine(deadline.toMillis());
    } catch (TimeoutException | ExecutionException e) {
      throw new IOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private Process start() throws IOException {
    String java = System.getProperty("java.home") + "/bin/java";
    Process process =
        new ProcessBuilder(
                java,
                "-Xmx" + heap,
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:TieredStopAtLevel=1",
                "-cp",
                System.getProperty("java.class.path"),
                WorkerMain.class.getName())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    return process;
  }

  @Override
  public void close() {
    synchronized (all) {
      all.forEach(Worker::kill);
    }
  }

  /** Um processo trabalhador com E/S de linha e leitura com prazo. */
  private static final class Worker {
    private final Process process;
    private final Writer writer;
    private final BufferedReader reader;

    Worker(Process process) {
      this.process = process;
      this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
      this.reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Espera a linha READY do trabalhador (inicialização da JVM e do motor). */
    void awaitReady() throws IOException {
      try {
        String line = readLine(START_TIMEOUT_MILLIS);
        if (!"READY".equals(line)) {
          throw new IOException("trabalhador não iniciou: " + line);
        }
      } catch (TimeoutException | ExecutionException e) {
        throw new IOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }

    void send(String line) throws IOException {
      writer.write(line + "\n");
      writer.flush();
    }

    String readLine(long timeoutMillis)
        throws IOException, TimeoutException, ExecutionException, InterruptedException {
      CompletableFuture<String> reply =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  String line = reader.readLine();
                  if (line == null) {
                    throw new IllegalStateException("worker encerrou");
                  }
                  return line;
                } catch (IOException e) {
                  throw new IllegalStateException(e);
                }
              });
      try {
        return reply.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      }
    }

    void kill() {
      process.destroyForcibly();
    }
  }
}
