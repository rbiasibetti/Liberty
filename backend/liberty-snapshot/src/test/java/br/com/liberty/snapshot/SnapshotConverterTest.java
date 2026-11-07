package br.com.liberty.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.snapshot.validation.FieldError;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SnapshotConverterTest {

  private static Migration step(int from, String key) {
    return new Migration() {
      @Override
      public int from() {
        return from;
      }

      @Override
      public ObjectNode apply(ObjectNode document) {
        document.put(key, "migrado-de-" + from);
        return document;
      }
    };
  }

  private static ObjectNode doc(int version) {
    return Fixtures.MAPPER.createObjectNode().put("schemaVersion", version).put("conteudo", "x");
  }

  private final SnapshotConverter converter =
      new SnapshotConverter(3, List.of(step(1, "do1"), step(2, "do2")));

  @Test
  void chainsEveryStepUpToTheLatestVersion() {
    var result = (ObjectNode) converter.toLatest(doc(1));

    assertThat(result.path("schemaVersion").asInt()).isEqualTo(3);
    assertThat(result.path("do1").asString()).isEqualTo("migrado-de-1");
    assertThat(result.path("do2").asString()).isEqualTo("migrado-de-2");
    assertThat(result.path("conteudo").asString()).isEqualTo("x");
  }

  @Test
  void startsFromTheDeclaredVersion() {
    var result = (ObjectNode) converter.toLatest(doc(2));

    assertThat(result.has("do1")).isFalse();
    assertThat(result.has("do2")).isTrue();
    assertThat(result.path("schemaVersion").asInt()).isEqualTo(3);
  }

  @Test
  void latestVersionPassesThroughUnchangedAndTheInputIsNeverMutated() {
    ObjectNode latest = doc(3);
    ObjectNode old = doc(1);

    assertThat(converter.toLatest(latest)).isEqualTo(latest);
    converter.toLatest(old);

    assertThat(old.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(old.has("do1")).isFalse();
  }

  @Test
  void refusesFutureInvalidAndNonObjectDocuments() {
    assertCode(() -> converter.toLatest(doc(4)), "future_version");
    assertCode(() -> converter.toLatest(doc(0)), "invalid_version");
    assertCode(() -> converter.toLatest(Fixtures.MAPPER.createObjectNode()), "invalid_version");
    assertCode(() -> converter.toLatest(Fixtures.MAPPER.createArrayNode()), "not_an_object");
    assertThat(converter.latest()).isEqualTo(3);
  }

  private static void assertCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            SnapshotValidationException.class,
            e -> assertThat(e.errors()).extracting(FieldError::code).containsExactly(code));
  }

  @Test
  void aMissingStepIsAConfigurationError() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SnapshotConverter(3, List.of(step(1, "a"))));
    assertThat(new SnapshotConverter(1, List.of()).latest()).isEqualTo(1);
  }
}
