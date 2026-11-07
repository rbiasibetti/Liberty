package br.com.liberty.scripts.pool;

import br.com.liberty.scripts.ScriptExecutor;
import br.com.liberty.scripts.worker.WorkerMain;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Pool de JVMs trabalhadoras (ADR 0017). Os trabalhadores sobem sob demanda, até {@code size}; cada
 * um roda uma execução por vez, com {@code -Xmx} próprio, ambiente vazio e sem credenciais. O pai
 * impõe o prazo duro (mata com SIGKILL quem o estourar), recicla o trabalhador após um número de
 * execuções ou idade, e repõe os que morrem.
 */
public final class WorkerPool implements ScriptExecutor, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);
  private static final String END = "\u0000EOF";

  /** Ajustes do pool. */
  public record Config(
      int size,
      String heap,
      Duration queueWait,
      Duration killGrace,
      int recycleRuns,
      Duration recycleAge,
      String classpath,
      int maxHostCalls) {}

  private final Config config;
  private final BlockingQueue<Worker> idle = new LinkedBlockingQueue<>();
  private final java.util.Set<Worker> all = ConcurrentHashMap.newKeySet();
  private final AtomicInteger total = new AtomicInteger();
  private final AtomicLong ids = new AtomicLong();
  private volatile boolean closed;

  public WorkerPool(Config config) {
    this.config = config;
  }

  @Override
  public Outcome run(Request request, HostCalls host) {
    Worker worker = acquire();
    boolean reusable = false;
    long start = System.nanoTime();
    try {
      Outcome outcome = worker.execute(ids.incrementAndGet(), request, host);
      reusable = outcome.status() != Status.WORKER_FAILURE && outcome.status() != Status.TIMEOUT;
      return outcome;
    } catch (RuntimeException | IOException e) {
      LOG.warn("Falha ao falar com o trabalhador de script: {}", e.toString());
      return new Outcome(
          Status.WORKER_FAILURE,
          null,
          "Trabalhador de script indisponível",
          0,
          "",
          (System.nanoTime() - start) / 1_000_000);
    } finally {
      release(worker, reusable);
    }
  }

  private Worker acquire() {
    Worker ready = idle.poll();
    if (ready != null) {
      return ready;
    }
    int current;
    while ((current = total.get()) < config.size()) {
      if (total.compareAndSet(current, current + 1)) {
        try {
          Worker created = new Worker(config);
          all.add(created);
          return created;
        } catch (IOException | RuntimeException e) {
          total.decrementAndGet();
          LOG.error("Não foi possível iniciar um trabalhador de script", e);
          throw new ScriptExecutor.BusyException();
        }
      }
    }
    try {
      ready = idle.poll(config.queueWait().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ScriptExecutor.BusyException();
    }
    if (ready == null) {
      throw new ScriptExecutor.BusyException();
    }
    return ready;
  }

  private void release(Worker worker, boolean reusable) {
    if (reusable && !closed && !worker.shouldRecycle()) {
      idle.add(worker);
      return;
    }
    worker.kill();
    all.remove(worker);
    total.decrementAndGet();
  }

  /** Trabalhadores vivos (para métricas e testes). */
  public int workers() {
    return total.get();
  }

  @Override
  public void close() {
    closed = true;
    all.forEach(Worker::kill);
    all.clear();
  }

  /** Um processo trabalhador com leitor dedicado e execução com prazo duro. */
  private static final class Worker {
    private final Config config;
    private final Process process;
    private final Writer writer;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final long bornNanos = System.nanoTime();
    private int runs;

    Worker(Config config) throws IOException {
      this.config = config;
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      ProcessBuilder builder =
          new ProcessBuilder(
              java,
              "-Xmx" + config.heap(),
              "-Xss1m",
              "-XX:+ExitOnOutOfMemoryError",
              "-XX:TieredStopAtLevel=1",
              "-cp",
              config.classpath(),
              WorkerMain.class.getName());
      builder.environment().clear();
      builder.directory(Path.of(System.getProperty("java.io.tmpdir")).toFile());
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      this.process = builder.start();
      this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
      Thread reader =
          new Thread(
              () -> {
                try (BufferedReader in =
                    new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = in.readLine()) != null) {
                    lines.add(line);
                  }
                } catch (IOException e) {
                  // o processo morreu; o pai percebe pelo fim da fila
                } finally {
                  lines.add(END);
                }
              },
              "script-worker-reader");
      reader.setDaemon(true);
      reader.start();
      awaitReady();
    }

    private void awaitReady() throws IOException {
      try {
        String line = lines.poll(30, TimeUnit.SECONDS);
        if (line == null
            || END.equals(line)
            || !"ready".equals(Protocol.read(line).path("type").asString())) {
          kill();
          throw new IOException("O trabalhador não iniciou");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        kill();
        throw new IOException(e);
      }
    }

    boolean shouldRecycle() {
      return runs >= config.recycleRuns()
          || System.nanoTime() - bornNanos > config.recycleAge().toNanos();
    }

    Outcome execute(long id, Request request, HostCalls host) throws IOException {
      runs++;
      long start = System.nanoTime();
      long deadline = start + request.timeout().plus(config.killGrace()).toNanos();
      send(Protocol.write(Protocol.run(id, request)));
      int calls = 0;
      while (true) {
        long remaining = deadline - System.nanoTime();
        String line;
        try {
          line = remaining <= 0 ? null : lines.poll(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          kill();
          throw new IOException(e);
        }
        if (line == null) {
          kill();
          return new Outcome(
              Status.TIMEOUT, null, "Prazo do script excedido", 0, "", elapsed(start));
        }
        if (END.equals(line)) {
          return new Outcome(
              Status.WORKER_FAILURE,
              null,
              "O trabalhador de script foi encerrado",
              0,
              "",
              elapsed(start));
        }
        JsonNode message = Protocol.read(line);
        switch (message.path("type").asString()) {
          case "call" -> {
            calls++;
            send(Protocol.write(answer(message, host, calls)));
          }
          case "result" -> {
            return toOutcome(message);
          }
          default -> LOG.warn("Mensagem inesperada do trabalhador de script");
        }
      }
    }

    private tools.jackson.databind.node.ObjectNode answer(
        JsonNode call, HostCalls host, int calls) {
      if (calls > config.maxHostCalls()) {
        return Protocol.callResult(false, null, "Limite de chamadas ao banco excedido");
      }
      List<String> p = call.path("params").valueStream().map(JsonNode::asString).toList();
      try {
        String value =
            switch (call.path("op").asString()) {
              case "named" -> host.named(p.get(0), p.get(1));
              case "lookup" -> host.lookup(p.get(0), p.get(1), p.get(2), p.get(3));
              case "fetch" -> host.fetch(p.get(0), p.get(1), p.get(2), p.get(3));
              default -> throw new IllegalArgumentException("Operação desconhecida");
            };
        return Protocol.callResult(true, value, null);
      } catch (RuntimeException e) {
        return Protocol.callResult(false, null, safeMessage(e));
      }
    }

    private static String safeMessage(RuntimeException e) {
      String message = e.getMessage();
      if (message == null || message.length() > 200) {
        return "Falha na consulta";
      }
      return message;
    }

    private static Outcome toOutcome(JsonNode m) {
      return new Outcome(
          Status.valueOf(m.path("status").asString()),
          m.has("json") ? m.get("json").asString() : null,
          m.has("message") ? m.get("message").asString() : null,
          m.path("httpStatus").asInt(),
          m.path("logs").asString(""),
          m.path("millis").asLong());
    }

    private static long elapsed(long start) {
      return (System.nanoTime() - start) / 1_000_000;
    }

    private void send(String line) throws IOException {
      writer.write(line + "\n");
      writer.flush();
    }

    void kill() {
      process.destroyForcibly();
    }
  }
}
