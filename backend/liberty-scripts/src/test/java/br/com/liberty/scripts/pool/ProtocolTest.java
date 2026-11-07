package br.com.liberty.scripts.pool;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.scripts.ScriptExecutor.Outcome;
import br.com.liberty.scripts.ScriptExecutor.Request;
import br.com.liberty.scripts.ScriptExecutor.Status;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProtocolTest {

  private static JsonNode roundTrip(JsonNode node) {
    return Protocol.read(Protocol.write(node));
  }

  @Test
  void messagesCarryTheirVersionAndFields() {
    assertThat(roundTrip(Protocol.ready()).path("v").asInt()).isEqualTo(Protocol.VERSION);

    JsonNode run =
        roundTrip(
            Protocol.run(
                7,
                new Request(
                    "function main(){}", "main", "{\"a\":1}", Duration.ofMillis(1500), 99)));
    assertThat(run.path("type").asString()).isEqualTo("run");
    assertThat(run.path("id").asLong()).isEqualTo(7);
    assertThat(run.path("input").asString()).isEqualTo("{\"a\":1}");
    assertThat(run.path("timeoutMs").asLong()).isEqualTo(1500);
    assertThat(run.path("maxStatements").asLong()).isEqualTo(99);

    JsonNode call = roundTrip(Protocol.call("named", new String[] {"q", "{}"}));
    assertThat(call.path("op").asString()).isEqualTo("named");
    assertThat(call.path("params")).hasSize(2);
  }

  @Test
  void callResultsAndOutcomesKeepOptionalFieldsOptional() {
    JsonNode ok = roundTrip(Protocol.callResult(true, "[1]", null));
    JsonNode bad = roundTrip(Protocol.callResult(false, null, "recusada"));
    assertThat(ok.path("ok").asBoolean()).isTrue();
    assertThat(ok.has("error")).isFalse();
    assertThat(bad.has("value")).isFalse();
    assertThat(bad.path("error").asString()).isEqualTo("recusada");

    JsonNode done = roundTrip(Protocol.result(3, new Outcome(Status.OK, "{}", null, 0, "log", 12)));
    JsonNode failed =
        roundTrip(
            Protocol.result(4, new Outcome(Status.BUSINESS_FAILURE, null, "estoque", 409, "", 1)));
    assertThat(done.path("json").asString()).isEqualTo("{}");
    assertThat(done.has("message")).isFalse();
    assertThat(failed.path("httpStatus").asInt()).isEqualTo(409);
    assertThat(failed.has("json")).isFalse();
  }
}
