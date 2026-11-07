package br.com.liberty.engine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.snapshot.SnapshotService;
import br.com.liberty.snapshot.model.Snapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LifecycleUnitTest {

  private static final SnapshotService SNAPSHOTS = new SnapshotService();

  private static Snapshot parse(String json) {
    return SNAPSHOTS.parse(json.getBytes(StandardCharsets.UTF_8)).model();
  }

  private static final String SELECT =
      "{\"schemaVersion\":1,\"integration\":{\"key\":\"lista\",\"name\":\"Lista\",\"method\":\"GET\",\"route\":\"/c/{id}\"},"
          + "\"request\":{\"params\":[{\"name\":\"id\",\"in\":\"path\",\"type\":\"integer\"}]},"
          + "\"operation\":{\"type\":\"SELECT\",\"select\":{\"table\":\"dbo.tb\",\"columns\":[\"a\"],\"single\":true}}%s}";

  private static final String INSERT =
      "{\"schemaVersion\":1,\"integration\":{\"key\":\"cria\",\"name\":\"Cria\",\"method\":\"POST\",\"route\":\"/c\"},"
          + "\"request\":{\"params\":[],\"bodySchema\":{\"type\":\"object\"}},"
          + "\"operation\":{\"type\":\"INSERT\",\"write\":{\"root\":{\"table\":\"dbo.pai\",\"fields\":[{\"column\":\"a\",\"source\":\"/a\"}],"
          + "\"primaryKey\":\"id\",\"children\":[{\"table\":\"dbo.filho\",\"arrayPath\":\"/f\",\"foreignKey\":\"pai_id\","
          + "\"fields\":[{\"column\":\"b\",\"source\":\"/b\"}]}]}}},\"idempotency\":{\"required\":true},\"response\":{\"successStatus\":200}}";

  @Test
  void checksIdentityBeforeExecuteOnSelectAndNamedQueries() {
    Snapshot ok = parse(String.format(SELECT, ""));
    Snapshot bad =
        parse(
            String.format(
                SELECT,
                ",\"scripts\":{\"beforeExecute\":\"function beforeExecute(i){return i.body;}\"},"
                    + "\"namedQueries\":[{\"name\":\"q\",\"sql\":\"DELETE FROM x\",\"params\":[]},"
                    + "{\"name\":\"r\",\"sql\":\"SELECT 1; SELECT 2\",\"params\":[]},"
                    + "{\"name\":\"s\",\"sql\":\"with a as (select 1 x) select * from a\",\"params\":[]}]"));

    assertThat(PublishChecks.check(ok, "lista", "GET", "/c/{id}")).isEmpty();
    assertThat(PublishChecks.check(ok, "outra", "GET", "/c/{id}"))
        .extracting("code")
        .containsExactly("identity_mismatch");
    assertThat(PublishChecks.check(bad, "lista", "GET", "/c/{id}"))
        .extracting("code")
        .containsExactly("before_execute_on_select", "not_a_simple_select", "not_a_simple_select");
  }

  @Test
  void listsEveryTableOfReadsAndWriteTrees() {
    assertThat(PublishChecks.tables(parse(String.format(SELECT, "")))).containsExactly("dbo.tb");
    assertThat(PublishChecks.tables(parse(INSERT))).containsExactly("dbo.pai", "dbo.filho");
  }

  @Test
  void openApiDescribesParametersBodiesSecurityAndErrors() {
    JsonNode doc =
        OpenApiGenerator.generate(
            "acme", "HML", List.of(parse(String.format(SELECT, "")), parse(INSERT)));

    assertThat(doc.path("openapi").asString()).isEqualTo("3.1.0");
    JsonNode read = doc.path("paths").path("/acme/api/c/{id}").path("get");
    assertThat(read.path("parameters")).hasSize(4);
    assertThat(read.path("responses").has("200")).isTrue();
    assertThat(read.path("responses").has("409")).isFalse();
    JsonNode write = doc.path("paths").path("/acme/api/c").path("post");
    assertThat(write.path("requestBody").path("content").has("application/json")).isTrue();
    assertThat(write.path("parameters").get(0).path("name").asString())
        .isEqualTo("Idempotency-Key");
    assertThat(write.path("parameters").get(0).path("required").asBoolean()).isTrue();
    assertThat(write.path("responses").has("200")).as("successStatus do snapshot").isTrue();
    assertThat(write.path("responses").path("409").path("content").has("application/problem+json"))
        .isTrue();
    assertThat(
            doc.path("components")
                .path("securitySchemes")
                .path("oauth2")
                .path("flows")
                .path("clientCredentials")
                .path("scopes")
                .has("integration:execute:cria"))
        .isTrue();
  }
}
