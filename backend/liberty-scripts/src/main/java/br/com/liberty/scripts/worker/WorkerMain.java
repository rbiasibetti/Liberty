package br.com.liberty.scripts.worker;

import br.com.liberty.scripts.ScriptExecutor;
import br.com.liberty.scripts.pool.Protocol;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Processo trabalhador (JVM própria com {@code -Xmx}): lê pedidos JSON-lines em stdin, executa no
 * {@link Sandbox} e responde em stdout. Durante uma execução o script pode pedir dados ao pai
 * ({@code call}/{@code callResult}). Se o heap acabar a JVM encerra (ExitOnOutOfMemoryError) e o
 * pai repõe o trabalhador. Nada além do protocolo pode ser escrito em stdout.
 */
public final class WorkerMain {

  private WorkerMain() {}

  public static void main(String[] args) throws Exception {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    try (Sandbox sandbox = new Sandbox();
        BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      out.println(Protocol.write(Protocol.ready()));
      String line;
      while ((line = in.readLine()) != null) {
        JsonNode message = Protocol.read(line);
        if (!"run".equals(message.path("type").asString())) {
          continue;
        }
        long id = message.path("id").asLong();
        HostBridge bridge = new HostBridge((op, params) -> call(out, in, op, params));
        var request =
            new ScriptExecutor.Request(
                message.path("script").asString(),
                message.path("function").asString(),
                message.path("input").asString(),
                Duration.ofMillis(message.path("timeoutMs").asLong()),
                message.path("maxStatements").asLong());
        out.println(Protocol.write(Protocol.result(id, sandbox.run(request, bridge))));
      }
    }
  }

  private static String call(PrintStream out, BufferedReader in, String op, String[] params) {
    ObjectNode call = Protocol.call(op, params);
    out.println(Protocol.write(call));
    try {
      String line = in.readLine();
      if (line == null) {
        throw new IllegalStateException("O pai encerrou o canal");
      }
      JsonNode reply = Protocol.read(line);
      if (!reply.path("ok").asBoolean()) {
        throw new IllegalStateException(reply.path("error").asString("Falha na chamada ao pai"));
      }
      return reply.path("value").asString("null");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Canal com o pai falhou", e);
    }
  }
}
