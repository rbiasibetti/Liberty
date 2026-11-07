package br.com.liberty.engine.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.snapshot.model.Operation;
import br.com.liberty.snapshot.model.Param;
import br.com.liberty.snapshot.model.Request;
import br.com.liberty.snapshot.model.Select;
import br.com.liberty.snapshot.model.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputParserTest {

  private static Snapshot snapshot(Select.PageSize pageSize) {
    Select select =
        new Select("dbo.t", List.of("id"), List.of(), List.of("id"), null, pageSize, null);
    List<Param> params =
        List.of(
            new Param("id", "path", "integer", null, null),
            new Param("q", "query", "string", false, 10),
            new Param("n", "query", "number", false, null),
            new Param("ok", "query", "boolean", false, null),
            new Param("obrig", "query", "string", true, null));
    return new Snapshot(
        1,
        null,
        new Request(params, null, null),
        new Operation("SELECT", select, null),
        null,
        null,
        null,
        null,
        null);
  }

  private static Map<String, List<String>> query(String... pairs) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.computeIfAbsent(pairs[i], k -> new java.util.ArrayList<>()).add(pairs[i + 1]);
    }
    return map;
  }

  private static List<String> violations(
      Snapshot snapshot, Map<String, String> path, Map<String, List<String>> query) {
    try {
      InputParser.parse(snapshot, path, query);
      return List.of();
    } catch (ProblemException e) {
      return e.violations().stream().map(v -> v.parameter() + ":" + v.code()).toList();
    }
  }

  private final Snapshot snapshot = snapshot(new Select.PageSize(20, 50));
  private final Map<String, String> path = Map.of("id", "7");

  @Test
  void acceptsAValidRequestAndAppliesPageDefaults() {
    ParsedInput input =
        InputParser.parse(
            snapshot, path, query("obrig", "x", "q", "abc", "n", "1.5", "ok", "true"));

    assertThat(input.values())
        .containsEntry("id", "7")
        .containsEntry("q", "abc")
        .containsEntry("obrig", "x");
    assertThat(input.limit()).isEqualTo(20);
    assertThat(input.offset()).isZero();
    assertThat(input.sort()).isNull();
  }

  @Test
  void defaultAndMaximumPageSizeComeFromTheSnapshotAndNeverExceedTheHardCap() {
    assertThat(InputParser.parse(snapshot(null), path, query("obrig", "x")).limit()).isEqualTo(100);
    assertThat(
            InputParser.parse(snapshot(new Select.PageSize(5000, 5000)), path, query("obrig", "x"))
                .limit())
        .isEqualTo(1000);
    assertThat(violations(snapshot, path, query("obrig", "x", "limit", "51")))
        .containsExactly("limit:out_of_range");
    assertThat(
            InputParser.parse(
                    snapshot,
                    path,
                    query("obrig", "x", "limit", "50", "offset", "100000", "sort", "-id"))
                .sort())
        .isEqualTo("-id");
  }

  @Test
  void reportsEveryProblemAtOnce() {
    List<String> found =
        violations(
            snapshot,
            Map.of("id", "abc"),
            query(
                "q",
                "x".repeat(11),
                "n",
                "um",
                "ok",
                "talvez",
                "limit",
                "0",
                "offset",
                "-1",
                "sort",
                "id; DROP",
                "extra",
                "1"));

    assertThat(found)
        .contains(
            "id:invalid_type",
            "q:too_long",
            "n:invalid_type",
            "ok:invalid_type",
            "limit:out_of_range",
            "offset:out_of_range",
            "sort:invalid_value",
            "extra:unknown_parameter",
            "obrig:required");
  }

  @Test
  void refusesRepeatedUnknownEmptyAndMalformedValues() {
    assertThat(violations(snapshot, path, query("obrig", "x", "q", "a", "q", "b")))
        .contains("q:repeated_parameter");
    assertThat(violations(snapshot, path, query("obrig", ""))).contains("obrig:empty_value");
    assertThat(violations(snapshot, path, query("obrig", "x", "limit", "dez")))
        .containsExactly("limit:invalid_type");
    assertThat(violations(snapshot, path, query("obrig", "x", "offset", "100001")))
        .containsExactly("offset:out_of_range");
    assertThat(violations(snapshot, path, query("obrig", "x", "limit", "99999999999999999999")))
        .containsExactly("limit:invalid_type");
    assertThat(violations(snapshot, path, query("obrig", "x", "n", "1".repeat(41))))
        .containsExactly("n:invalid_type");
    assertThat(violations(snapshot, path, query("obrig", "x", "n", "1e5"))).isEmpty();
  }

  @Test
  void unknownParameterNamesAreNeverEchoedWhenUnsafe() {
    assertThatThrownBy(
            () ->
                InputParser.parse(snapshot, path, query("obrig", "x", "a'; DROP TABLE t;--", "1")))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e ->
                assertThat(e.violations())
                    .extracting(v -> v.parameter())
                    .contains("<inválido>")
                    .doesNotContain("a'; DROP TABLE t;--"));
  }

  @Test
  void problemExceptionCarriesStatusTypeAndTitle() {
    assertThatThrownBy(() -> InputParser.parse(snapshot, path, query()))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e -> {
              assertThat(e.status()).isEqualTo(400);
              assertThat(e.type()).isEqualTo("urn:liberty:problem:validation");
              assertThat(e.title()).isNotBlank();
              assertThat(e.headers()).isEmpty();
            });
  }
}
