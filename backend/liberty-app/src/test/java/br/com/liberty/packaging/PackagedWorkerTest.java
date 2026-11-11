package br.com.liberty.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.scripts.ScriptExecutor.HostCalls;
import br.com.liberty.scripts.ScriptExecutor.Request;
import br.com.liberty.scripts.ScriptExecutor.Status;
import br.com.liberty.scripts.pool.WorkerPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * O trabalhador de script precisa funcionar só com o conteúdo de {@code
 * liberty-scripts/target/worker-lib}, que é exatamente o que a imagem de produção leva (um jar
 * executável do Spring Boot não serve de {@code -cp}). O classpath do teste não entra: só a pasta e
 * o curinga, como na imagem. Exige o pacote do módulo liberty-scripts (rode {@code ./mvnw verify},
 * não só {@code test}).
 */
class PackagedWorkerTest {

  private static final HostCalls HOST =
      new HostCalls() {
        @Override
        public String named(String name, String paramsJson) {
          return "[{\"n\":1}]";
        }

        @Override
        public String lookup(String table, String keyColumn, String keyJson, String valueColumn) {
          return "null";
        }

        @Override
        public String fetch(String method, String url, String headersJson, String bodyJson) {
          return "{\"status\":200,\"ok\":true,\"headers\":{},\"body\":\"\"}";
        }

        @Override
        public String publish(String queue, String payloadJson) {
          return "{\"queued\":false,\"simulated\":true}";
        }
      };

  private static Path workerLib() {
    Path lib =
        Path.of("..", "liberty-scripts", "target", "worker-lib").toAbsolutePath().normalize();
    assertThat(lib)
        .as("rode ./mvnw verify: o pacote do liberty-scripts cria a pasta worker-lib")
        .isDirectory();
    return lib;
  }

  @Test
  void theWorkerLibFolderAloneRunsScriptsWithTheWholeHostApi() throws Exception {
    Path lib = workerLib();
    assertThat(Files.list(lib).map(p -> p.getFileName().toString()))
        .anyMatch(n -> n.startsWith("liberty-scripts-"))
        .anyMatch(n -> n.startsWith("js-language-"))
        .anyMatch(n -> n.startsWith("jackson-databind-"));
    try (var pool =
        new WorkerPool(
            new WorkerPool.Config(
                1,
                "128m",
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                1000,
                Duration.ofMinutes(10),
                lib + "/*",
                20))) {
      var ok =
          pool.run(
              new Request(
                  "function main(i) { return { soma: i.a + i.b, db: db.named('x', {})[0].n,"
                      + " http: http.fetch('https://api.exemplo.com/').status,"
                      + " fila: queue.publish('webhook', {}).simulated }; }",
                  "main",
                  "{\"a\":2,\"b\":3}",
                  Duration.ofSeconds(10),
                  5_000_000),
              HOST);
      var loop =
          pool.run(
              new Request(
                  "function main(i) { while (true) {} }",
                  "main",
                  "{}",
                  Duration.ofSeconds(1),
                  5_000_000),
              HOST);
      var after =
          pool.run(
              new Request(
                  "function main(i) { return 1; }",
                  "main",
                  "{}",
                  Duration.ofSeconds(10),
                  5_000_000),
              HOST);

      assertThat(ok.status()).as(String.valueOf(ok.message())).isEqualTo(Status.OK);
      assertThat(ok.json()).isEqualTo("{\"soma\":5,\"db\":1,\"http\":200,\"fila\":true}");
      assertThat(loop.status()).isEqualTo(Status.TIMEOUT);
      assertThat(after.status())
          .as("o trabalhador foi trocado e o próximo script roda")
          .isEqualTo(Status.OK);
    }
  }
}
