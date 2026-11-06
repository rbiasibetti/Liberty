package br.com.liberty.poc.script;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Scripts hostis que o isolamento em processo contém: acesso ao host, estado, CPU e saída. */
class InProcessContainmentTest {

  private static ScriptRunner runner;

  @BeforeAll
  static void start() {
    runner = new ScriptRunner();
  }

  @AfterAll
  static void stop() {
    runner.close();
  }

  private static ScriptRunner.Result run(String attack) {
    return runner.run(
        HostileHarness.ATTACKS.get(attack), "f", "{}", Duration.ofSeconds(2), 1_000_000L);
  }

  @Test
  void runsLegitimateScripts() {
    var r =
        runner.run(
            "function f(i){ return {total: i.a + i.b, c: api.lookup('t','k')}; }",
            "f",
            "{\"a\":1,\"b\":2}",
            Duration.ofSeconds(5),
            1_000_000);

    assertThat(r.status()).isEqualTo(ScriptRunner.Status.OK);
    assertThat(r.json()).isEqualTo("{\"total\":3,\"c\":\"t:k\"}");
  }

  @Test
  void businessFailureIsDistinctFromScriptError() {
    var r =
        runner.run(
            "function f(i){ api.fail(422, 'idade invalida'); }",
            "f",
            "{}",
            Duration.ofSeconds(2),
            1_000_000);

    assertThat(r.status()).isEqualTo(ScriptRunner.Status.BUSINESS_FAILURE);
    assertThat(r.message()).contains("422");
  }

  @Test
  void statementLimitStopsTightLoops() {
    var r = run("laco-infinito");

    assertThat(r.status()).isEqualTo(ScriptRunner.Status.STATEMENT_LIMIT);
    assertThat(r.millis()).isLessThan(2_000);
  }

  @Test
  void deadlineCancelsLoopsThatTheStatementLimitDoesNotCatch() {
    var r =
        runner.run(
            HostileHarness.ATTACKS.get("laco-com-chamadas"),
            "f",
            "{}",
            Duration.ofMillis(500),
            Long.MAX_VALUE / 2);

    assertThat(r.status()).isEqualTo(ScriptRunner.Status.TIMEOUT);
    assertThat(r.millis()).isBetween(400L, 1_500L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"java-type", "load-arquivo"})
  void hostAndFileAccessIsDenied(String attack) {
    assertThat(run(attack).status()).isEqualTo(ScriptRunner.Status.DENIED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "packages",
        "java-global",
        "reflexao-no-api",
        "metodo-nao-exportado",
        "require",
        "atomics-wait",
        "timer",
        "recursao-infinita",
        "bomba-de-string"
      })
  void everythingElseFailsInsideTheScript(String attack) {
    var r = run(attack);

    assertThat(r.status()).isEqualTo(ScriptRunner.Status.SCRIPT_ERROR);
    assertThat(r.json()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"regex-catastrofica", "import-dinamico"})
  void terminatesAndLeaksNothing(String attack) {
    var r = run(attack);

    assertThat(Set.of(ScriptRunner.Status.OK, ScriptRunner.Status.TIMEOUT)).contains(r.status());
    assertThat(r.millis()).isLessThan(3_000);
    assertThat(String.valueOf(r.json())).doesNotContain("root:");
  }

  @Test
  void logOutputIsBounded() {
    var r = run("flood-de-log");

    assertThat(r.logs().length()).isLessThanOrEqualTo(64 * 1024);
  }

  @Test
  void noStateSurvivesBetweenExecutions() {
    var first = run("vazamento-de-estado");
    var second = run("vazamento-de-estado");

    assertThat(first.json()).isEqualTo("1");
    assertThat(second.json()).isEqualTo("1");
  }
}
