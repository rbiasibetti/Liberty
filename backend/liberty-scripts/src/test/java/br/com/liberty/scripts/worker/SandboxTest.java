package br.com.liberty.scripts.worker;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.scripts.ScriptExecutor.Request;
import br.com.liberty.scripts.ScriptExecutor.Status;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** O sandbox em processo (o isolamento por processo é coberto no WorkerPoolTest). */
class SandboxTest {

  private static final Sandbox SANDBOX = new Sandbox();
  private static final HostBridge BRIDGE =
      new HostBridge(
          (op, args) -> {
            if (op.equals("named") && args[0].equals("quebra")) {
              throw new IllegalStateException("recusada");
            }
            return "\"" + op + ":" + String.join(",", args).replace("\"", "") + "\"";
          });

  @AfterAll
  static void close() {
    SANDBOX.close();
  }

  private static br.com.liberty.scripts.ScriptExecutor.Outcome run(String script, long statements) {
    return SANDBOX.run(
        new Request(
            script,
            "main",
            "{\"n\":2}",
            Duration.ofMillis(statements < 1_000_000 ? 1500 : 8000),
            statements),
        BRIDGE);
  }

  @Test
  void runsFunctionsAndSerializesResults() {
    var ok = run("function main(i) { return { dobro: i.n * 2 }; }", 5_000_000);
    var nothing = run("function main(i) { return null; }", 5_000_000);

    assertThat(ok.status()).isEqualTo(Status.OK);
    assertThat(ok.json()).isEqualTo("{\"dobro\":4}");
    assertThat(nothing.json()).isEqualTo("null");
  }

  @Test
  void callsGoThroughTheBridgeAndErrorsAreCatchable() {
    var o =
        run(
            "function main(i) { let m; try { db.named('quebra', {}); } catch (e) { m = e.message; }"
                + " return { m, l: db.lookup('t', 'k', 1, 'v') }; }",
            5_000_000);

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.json()).contains("recusada").contains("lookup:t,k,1,v");
  }

  @Test
  void failBecomesABusinessFailureWithAValidStatus() {
    var o = run("function main() { fail(409, 'duplicado'); }", 5_000_000);
    var odd = run("function main() { fail(302, 'x'.repeat(500)); }", 5_000_000);

    assertThat(o.status()).isEqualTo(Status.BUSINESS_FAILURE);
    assertThat(o.httpStatus()).isEqualTo(409);
    assertThat(odd.httpStatus()).isEqualTo(422);
    assertThat(odd.message()).hasSize(300);
  }

  @Test
  void limitsAndErrorsAreClassified() {
    assertThat(run("function main() { while (true) {} }", 50_000).status())
        .isEqualTo(Status.TIMEOUT);
    assertThat(run("function main() { throw new Error('x'); }", 100_000).status())
        .isEqualTo(Status.SCRIPT_ERROR);
    assertThat(run("function outra() {}", 100_000).message()).contains("main");
    assertThat(run("function main() { return 'a'.repeat(2000000); }", 100_000_000).status())
        .isEqualTo(Status.SCRIPT_ERROR);
  }

  @Test
  void consoleOutputIsCapturedAndBounded() {
    var o =
        run(
            "function main() { for (let i = 0; i < 5000; i++) console.log('x'.repeat(100)); return 1; }",
            10_000_000);

    assertThat(o.status()).isEqualTo(Status.OK);
    assertThat(o.logs().length()).isLessThanOrEqualTo(Sandbox.MAX_LOG_BYTES);
  }
}
