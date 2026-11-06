package br.com.liberty.poc.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * O que o isolamento em processo garante e o em memória não: heap próprio e kill do trabalhador.
 */
class ProcessIsolationTest {

  private static final String NORMAL =
      "function f(i){ var s=0; for(var k=0;k<2000;k++) s+=k; return {s:s}; }";

  private static ScriptRunner.Result normal(ProcessScriptPool pool) throws Exception {
    return pool.run(NORMAL, "f", "{}", Duration.ofSeconds(5), 10_000_000);
  }

  @Test
  void hostileScriptsDoNotAffectOtherRequests() throws Exception {
    try (ProcessScriptPool pool = new ProcessScriptPool(4, "128m", Duration.ofSeconds(1));
        ExecutorService executor = Executors.newFixedThreadPool(5)) {
      AtomicBoolean stop = new AtomicBoolean();
      AtomicInteger ok = new AtomicInteger();
      AtomicInteger bad = new AtomicInteger();
      List<Future<?>> loops = new ArrayList<>();
      for (int t = 0; t < 3; t++) {
        loops.add(
            executor.submit(
                () -> {
                  while (!stop.get()) {
                    var r = normal(pool);
                    (r.status() == ScriptRunner.Status.OK ? ok : bad).incrementAndGet();
                  }
                  return null;
                }));
      }
      Thread.sleep(300);
      for (String attack : List.of("bomba-de-memoria", "bomba-de-objetos", "laco-infinito")) {
        var r =
            pool.run(
                HostileHarness.ATTACKS.get(attack),
                "f",
                "{}",
                Duration.ofSeconds(1),
                5_000_000_000L);
        assertThat(
                Set.of(
                    ScriptRunner.Status.TIMEOUT,
                    ScriptRunner.Status.WORKER_FAILURE,
                    ScriptRunner.Status.SCRIPT_ERROR))
            .contains(r.status());
      }
      stop.set(true);
      for (Future<?> loop : loops) {
        loop.get();
      }

      assertThat(ok.get()).isPositive();
      assertThat(bad.get()).isZero();
      assertThat(normal(pool).status()).isEqualTo(ScriptRunner.Status.OK);
    }
  }

  @Test
  void workerThatRunsOutOfHeapIsReplaced() throws Exception {
    try (ProcessScriptPool pool = new ProcessScriptPool(2, "48m", Duration.ofSeconds(1))) {
      var bomb =
          pool.run(
              HostileHarness.ATTACKS.get("bomba-de-memoria"),
              "f",
              "{}",
              Duration.ofSeconds(5),
              5_000_000_000L);

      assertThat(bomb.status()).isNotEqualTo(ScriptRunner.Status.OK);
      assertThat(normal(pool).status()).isEqualTo(ScriptRunner.Status.OK);
      assertThat(normal(pool).status()).isEqualTo(ScriptRunner.Status.OK);
    }
  }

  @Test
  void parentKillsAWorkerThatIgnoresItsOwnDeadline() throws Exception {
    try (ProcessScriptPool pool = new ProcessScriptPool(1, "128m", Duration.ofSeconds(1))) {
      long start = System.nanoTime();
      var r =
          pool.run(
              HostileHarness.ATTACKS.get("laco-infinito"),
              "f",
              "{}",
              Duration.ofSeconds(60),
              Long.MAX_VALUE / 2,
              Duration.ofSeconds(1));
      long millis = (System.nanoTime() - start) / 1_000_000;

      assertThat(r.status()).isEqualTo(ScriptRunner.Status.WORKER_FAILURE);
      assertThat(millis).isLessThan(8_000);
      assertThat(normal(pool).status()).isEqualTo(ScriptRunner.Status.OK);
    }
  }
}
