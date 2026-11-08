package br.com.liberty.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.scripts.ScriptExecutor.HostCalls;
import br.com.liberty.scripts.ScriptExecutor.Outcome;
import br.com.liberty.scripts.ScriptExecutor.Request;
import br.com.liberty.scripts.ScriptExecutor.Status;
import br.com.liberty.scripts.pool.WorkerPool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** O pool de trabalhadores de verdade (processos), com scripts normais e hostis. */
class WorkerPoolTest {

  private static WorkerPool pool;

  private static final HostCalls HOST =
      new HostCalls() {
        @Override
        public String named(String name, String paramsJson) {
          if (name.equals("quebra")) {
            throw new IllegalStateException("consulta recusada");
          }
          return "[{\"nome\":\"" + name + "\",\"params\":" + paramsJson + "}]";
        }

        @Override
        public String lookup(String table, String keyColumn, String keyJson, String valueColumn) {
          return keyJson.equals("1") ? "\"" + table + "." + valueColumn + "\"" : "null";
        }

        @Override
        public String publish(String queue, String payloadJson) {
          if (queue.equals("negada")) {
            throw new IllegalArgumentException("Fila desconhecida");
          }
          return "{\"queued\":true,\"queue\":\"" + queue + "\",\"n\":" + payloadJson.length() + "}";
        }

        @Override
        public String fetch(String method, String url, String headersJson, String bodyJson) {
          if (url.contains("proibido")) {
            throw new IllegalArgumentException("Host não liberado para este tenant");
          }
          return "{\"status\":200,\"ok\":true,\"headers\":{},\"body\":\""
              + method
              + " "
              + url
              + " "
              + headersJson.replace("\"", "'")
              + " "
              + bodyJson.replace("\\", "").replace("\"", "'")
              + "\"}";
        }
      };

  private static WorkerPool newPool(int size, int recycleRuns, int maxCalls) {
    return new WorkerPool(
        new WorkerPool.Config(
            size,
            "128m",
            Duration.ofMillis(300),
            Duration.ofSeconds(1),
            recycleRuns,
            Duration.ofMinutes(10),
            System.getProperty("java.class.path"),
            maxCalls));
  }

  @BeforeAll
  static void start() {
    pool = newPool(3, 1000, 20);
  }

  @AfterAll
  static void stop() {
    pool.close();
  }

  private static Outcome run(String script, String input) {
    return run(pool, script, input, 2000);
  }

  private static Outcome run(WorkerPool target, String script, String input, long timeoutMs) {
    return target.run(
        new Request(script, "main", input, Duration.ofMillis(timeoutMs), 5_000_000), HOST);
  }

  @Test
  void normalScriptsTransformJson() {
    Outcome o =
        run(
            "function main(i) { return { total: i.a + i.b, nome: i.nome.toUpperCase() }; }",
            "{\"a\":2,\"b\":3,\"nome\":\"ana\"}");

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json()).isEqualTo("{\"total\":5,\"nome\":\"ANA\"}");
  }

  @Test
  void httpFetchGoesThroughTheParentWithMethodHeadersAndBody() {
    Outcome o =
        run(
            "function main(i) { const r = http.fetch('https://api.exemplo.com/x', { method: 'POST',"
                + " headers: { 'X-A': '1' }, body: { n: 1 } }); return { s: r.status, b: r.body }; }",
            "{}");

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json())
        .contains("POST https://api.exemplo.com/x")
        .contains("X-A")
        .contains("application/json")
        .contains(":1}");
  }

  @Test
  void queuePublishGoesThroughTheParentAndRefusalsAreCatchable() {
    Outcome o =
        run(
            "function main(i) { const a = queue.publish('webhook', { url: 'x' });"
                + " let e; try { queue.publish('negada', {}); } catch (x) { e = x.message; }"
                + " return { q: a.queue, queued: a.queued, e: e }; }",
            "{}");

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json())
        .isEqualTo("{\"q\":\"webhook\",\"queued\":true,\"e\":\"Fila desconhecida\"}");
  }

  @Test
  void httpFetchRefusalsSurfaceAsCatchableErrorsAndThereIsNoOtherNetwork() {
    Outcome refused =
        run(
            "function main(i) { try { http.fetch('https://proibido.exemplo.com/'); } catch (e) { return e.message; } }",
            "{}");
    Outcome noFetchApi =
        run(
            "function main(i) { return [typeof fetch, typeof XMLHttpRequest, typeof WebSocket, typeof require].join(','); }",
            "{}");

    assertThat(refused.json()).contains("não liberado");
    assertThat(noFetchApi.json()).isEqualTo("\"undefined,undefined,undefined,undefined\"");
  }

  @Test
  void scriptsReachDataOnlyThroughTheParent() {
    Outcome o =
        run(
            "function main(i) { const r = db.named('clientes', { id: i.id });"
                + " return { q: r[0], v: db.lookup('tb', 'id', 1, 'nome'), x: db.lookup('tb', 'id', 2, 'nome') }; }",
            "{\"id\":7}");

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json())
        .isEqualTo(
            "{\"q\":{\"nome\":\"clientes\",\"params\":{\"id\":7}},\"v\":\"tb.nome\",\"x\":null}");
  }

  @Test
  void failedHostCallsSurfaceAsCatchableErrors() {
    Outcome o =
        run(
            "function main(i) { try { db.named('quebra', {}); } catch (e) { return e.message; } }",
            "{}");

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json()).contains("consulta recusada");
  }

  @Test
  void hostCallsAreLimitedPerExecution() {
    var limited = newPool(1, 1000, 3);
    try {
      Outcome o =
          run(
              limited,
              "function main(i) { for (let k = 0; k < 10; k++) { db.named('q', {}); } return 1; }",
              "{}",
              2000);

      assertThat(o.status()).isEqualTo(Status.SCRIPT_ERROR);
      assertThat(o.message()).contains("Limite de chamadas");
    } finally {
      limited.close();
    }
  }

  @Test
  void businessFailuresCarryTheRequestedStatus() {
    Outcome o = run("function main(i) { fail(422, 'estoque insuficiente'); }", "{}");
    Outcome odd = run("function main(i) { fail(200, 'x'); }", "{}");

    assertThat(o.status()).isEqualTo(Status.BUSINESS_FAILURE);
    assertThat(o.httpStatus()).isEqualTo(422);
    assertThat(o.message()).isEqualTo("estoque insuficiente");
    assertThat(odd.httpStatus()).isEqualTo(422);
  }

  @Test
  void scriptErrorsAreContainedAndMissingFunctionsReported() {
    assertThat(run("function main(i) { throw new Error('boom'); }", "{}").status())
        .isEqualTo(Status.SCRIPT_ERROR);
    assertThat(run("function outra(i) { return 1; }", "{}").message()).contains("main");
    assertThat(run("function main(i { ", "{}").status()).isEqualTo(Status.SCRIPT_ERROR);
  }

  @Test
  void javaFilesNetworkAndModulesAreUnreachable() {
    String[] attacks = {
      "function main() { return Java.type('java.lang.System').getenv('PATH'); }",
      "function main() { return String(Packages.java.lang.Runtime.getRuntime()); }",
      "function main() { return load('/etc/passwd'); }",
      "function main() { return require('fs').readFileSync('/etc/passwd'); }",
      "function main() { return typeof process !== 'undefined' ? process.env.PATH : null; }",
      "function main() { return String(db.constructor.constructor('return this')().__host); }",
      "function main() { return JSON.stringify(Object.getOwnPropertyNames(globalThis).filter(n => n === '__host')); }",
    };
    for (String attack : attacks) {
      Outcome o = run(attack, "{}");
      if (o.status() == Status.OK) {
        assertThat(o.json()).as(attack).isIn("null", "\"undefined\"", "\"[]\"", "[]", "\"[]\"");
      } else {
        assertThat(o.status()).as(attack).isEqualTo(Status.SCRIPT_ERROR);
      }
    }
  }

  @Test
  void runawayScriptsAreCutOffAndTheWorkerIsReplaced() {
    long start = System.nanoTime();
    Outcome loop = run("function main() { while (true) {} }", "{}");
    Outcome regex = run("function main() { return /(a+)+$/.test('a'.repeat(60) + 'b'); }", "{}");
    long seconds = (System.nanoTime() - start) / 1_000_000_000;

    assertThat(loop.status()).isEqualTo(Status.TIMEOUT);
    assertThat(regex.status()).isIn(Status.TIMEOUT, Status.OK);
    assertThat(seconds).isLessThan(15);
    assertThat(run("function main() { return 1; }", "{}").status()).isEqualTo(Status.OK);
  }

  @Test
  void memoryBombsKillOnlyTheirWorker() {
    Outcome bomb =
        run(
            "function main() { const a = []; while (true) { a.push(new Array(1e6).fill('x'.repeat(100))); } }",
            "{}");

    assertThat(bomb.status()).isIn(Status.WORKER_FAILURE, Status.TIMEOUT, Status.SCRIPT_ERROR);
    assertThat(run("function main() { return 'vivo'; }", "{}").json()).isEqualTo("\"vivo\"");
  }

  @Test
  void normalRunsAreNotAffectedByABombRunningBesideThem() throws Exception {
    AtomicInteger failures = new AtomicInteger();
    try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
      Future<Outcome> bomb =
          executor.submit(
              () ->
                  run(
                      "function main() { const a = []; while (true) { a.push(new Array(1e6).fill('y')); } }",
                      "{}"));
      List<Future<?>> normal = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        normal.add(
            executor.submit(
                () -> {
                  for (int n = 0; n < 40; n++) {
                    Outcome o = run("function main(i) { return i.n * 2; }", "{\"n\":" + n + "}");
                    if (o.status() != Status.OK || !o.json().equals(String.valueOf(n * 2))) {
                      failures.incrementAndGet();
                    }
                  }
                }));
      }
      for (Future<?> f : normal) {
        f.get();
      }
      bomb.get();
    }

    assertThat(failures.get()).isZero();
  }

  @Test
  void consoleFloodAndHugeResultsAreBounded() {
    Outcome flood =
        run(
            "function main() { for (let i = 0; i < 100000; i++) console.log('x'.repeat(100)); return 1; }",
            "{}");
    Outcome huge = run("function main() { return 'z'.repeat(2000000); }", "{}");

    assertThat(flood.status()).isEqualTo(Status.OK);
    assertThat(flood.logs().length()).isLessThanOrEqualTo(64 * 1024);
    assertThat(huge.status()).isEqualTo(Status.SCRIPT_ERROR);
  }

  @Test
  void nothingSurvivesBetweenExecutions() {
    run("var guardado = 1; function main() { globalThis.segredo = 'x'; return 1; }", "{}");

    Outcome o =
        run("function main() { return typeof globalThis.segredo + typeof guardado; }", "{}");

    assertThat(o.json()).isEqualTo("\"undefinedundefined\"");
  }

  @Test
  void aFullPoolRejectsInsteadOfQueueingForever() throws Exception {
    var single = newPool(1, 1000, 20);
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Outcome> slow =
          executor.submit(
              () ->
                  run(
                      single,
                      "function main() { const t = Date.now(); while (Date.now() - t < 1200) {} return 1; }",
                      "{}",
                      5000));
      Thread.sleep(700);

      assertThatThrownBy(() -> run(single, "function main() { return 1; }", "{}", 2000))
          .isInstanceOf(ScriptExecutor.BusyException.class);
      assertThat(slow.get().status()).isEqualTo(Status.OK);
    } finally {
      single.close();
    }
  }

  @Test
  void workersAreRecycledAfterTheConfiguredNumberOfRuns() {
    var recycling = newPool(1, 2, 20);
    try {
      for (int i = 0; i < 5; i++) {
        assertThat(run(recycling, "function main() { return 1; }", "{}", 2000).status())
            .isEqualTo(Status.OK);
      }
      assertThat(recycling.workers()).isLessThanOrEqualTo(1);
    } finally {
      recycling.close();
    }
  }
}
