package br.com.liberty.poc.script;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Processo trabalhador: lê pedidos linha a linha (id, script, função, entrada, prazo, instruções)
 * em Base64 separado por TAB, executa no {@link ScriptRunner} e responde (id, status, corpo, ms).
 * Roda com {@code -Xmx} próprio; se o heap acabar a JVM encerra (ExitOnOutOfMemoryError).
 */
public final class WorkerMain {

  private WorkerMain() {}

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    try (ScriptRunner runner = new ScriptRunner();
        BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      out.println("READY");
      String line;
      while ((line = in.readLine()) != null) {
        String[] f = line.split("\t", -1);
        var r =
            runner.run(
                decode(f[1]),
                f[2],
                decode(f[3]),
                Duration.ofMillis(Long.parseLong(f[4])),
                Long.parseLong(f[5]));
        String body = r.status() == ScriptRunner.Status.OK ? r.json() : r.message();
        out.println(
            f[0] + "\t" + r.status() + "\t" + encode(String.valueOf(body)) + "\t" + r.millis());
      }
    }
  }

  static String encode(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }
}
