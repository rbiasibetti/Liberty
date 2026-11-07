package br.com.liberty.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TransformSpecsTest {

  private static JsonNode args(String json) {
    return json == null ? null : Fixtures.MAPPER.readTree(json);
  }

  private static boolean ok(String fn, String json) {
    return TransformSpecs.check(fn, args(json)).isEmpty();
  }

  @Test
  void noArgumentFunctions() {
    for (String fn : new String[] {"trim", "upper", "lower", "digits"}) {
      assertThat(ok(fn, null)).as(fn).isTrue();
      assertThat(ok(fn, "[]")).as(fn).isTrue();
      assertThat(ok(fn, "[1]")).as(fn + " com argumento").isFalse();
    }
  }

  @Test
  void substringNeedsTwoOrderedNonNegativeIntegers() {
    assertThat(ok("substring", "[0, 5]")).isTrue();
    assertThat(ok("substring", "[2, 2]")).isTrue();
    assertThat(ok("substring", "[3, 1]")).isFalse();
    assertThat(ok("substring", "[-1, 2]")).isFalse();
    assertThat(ok("substring", "[\"a\", 2]")).isFalse();
    assertThat(ok("substring", "[1]")).isFalse();
    assertThat(ok("substring", null)).isFalse();
  }

  @Test
  void replaceNeedsTwoBoundedTextsAndANonEmptyFrom() {
    assertThat(ok("replace", "[\".\", \"\"]")).isTrue();
    assertThat(ok("replace", "[\"\", \"x\"]")).isFalse();
    assertThat(ok("replace", "[1, \"x\"]")).isFalse();
    assertThat(ok("replace", "[\"" + "a".repeat(201) + "\", \"x\"]")).isFalse();
  }

  @Test
  void toDateNeedsAValidPattern() {
    assertThat(ok("toDate", "[\"yyyy-MM-dd\"]")).isTrue();
    assertThat(ok("toDate", "[\"dd/MM/yyyy HH:mm\"]")).isTrue();
    assertThat(ok("toDate", "[\"invalid{\"]")).isFalse();
    assertThat(ok("toDate", "[1]")).isFalse();
    assertThat(ok("toDate", "[]")).isFalse();
  }

  @Test
  void toDecimalNeedsAScaleBetweenZeroAndEighteen() {
    assertThat(ok("toDecimal", "[0]")).isTrue();
    assertThat(ok("toDecimal", "[18]")).isTrue();
    assertThat(ok("toDecimal", "[19]")).isFalse();
    assertThat(ok("toDecimal", "[-1]")).isFalse();
    assertThat(ok("toDecimal", "[\"2\"]")).isFalse();
  }

  @Test
  void defaultTakesOneSimpleValue() {
    assertThat(ok("default", "[\"x\"]")).isTrue();
    assertThat(ok("default", "[0]")).isTrue();
    assertThat(ok("default", "[null]")).isTrue();
    assertThat(ok("default", "[{\"a\":1}]")).isFalse();
    assertThat(ok("default", "[[1]]")).isFalse();
    assertThat(ok("default", "[]")).isFalse();
  }

  @Test
  void mapTakesAnObjectOfSimpleValues() {
    assertThat(ok("map", "[{\"M\":\"Masculino\",\"F\":\"Feminino\"}]")).isTrue();
    assertThat(ok("map", "[{}]")).isFalse();
    assertThat(ok("map", "[{\"a\":{\"b\":1}}]")).isFalse();
    assertThat(ok("map", "[[1]]")).isFalse();
    StringBuilder big = new StringBuilder("[{");
    for (int i = 0; i < 101; i++) {
      big.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
    }
    assertThat(ok("map", big.append("}]").toString())).isFalse();
  }

  @Test
  void unknownFunctionsAndNonListArgumentsAreRefused() {
    assertThat(ok("eval", null)).isFalse();
    assertThat(ok("trim", "{\"a\":1}")).isFalse();
  }
}
