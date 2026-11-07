package br.com.liberty.engine.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.snapshot.model.Transform;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransformsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final Transforms transforms = new Transforms();

  private static Transform t(String fn, String... jsonArgs) {
    List<JsonNode> args = new ArrayList<>();
    for (String a : jsonArgs) {
      args.add(JSON.readTree(a));
    }
    return new Transform(fn, args);
  }

  private String run(String value, Transform... chain) {
    return transforms.apply(value, List.of(chain));
  }

  @Test
  void textFunctions() {
    assertThat(run("  Olá  ", t("trim"))).isEqualTo("Olá");
    assertThat(run("aBç", t("upper"))).isEqualTo("ABÇ");
    assertThat(run("aBÇ", t("lower"))).isEqualTo("abç");
    assertThat(run("12.345.678/0001-90", t("digits"))).isEqualTo("12345678000190");
    assertThat(run("abcdef", t("substring", "1", "4"))).isEqualTo("bcd");
    assertThat(run("abc", t("substring", "2", "99"))).isEqualTo("c");
    assertThat(run("abc", t("substring", "5", "9"))).isEmpty();
    assertThat(run("a-b-c", t("replace", "\"-\"", "\"+\""))).isEqualTo("a+b+c");
  }

  @Test
  void chainsApplyInOrder() {
    assertThat(run("  ab-12 ", t("trim"), t("upper"), t("replace", "\"-\"", "\"\"")))
        .isEqualTo("AB12");
  }

  @Test
  void datesAndDecimals() {
    assertThat(run("31/12/2026", t("toDate", "\"dd/MM/yyyy\""))).isEqualTo("2026-12-31");
    assertThat(run("12.345", t("toDecimal", "2"))).isEqualTo("12.35");
    assertThat(run(" 7 ", t("toDecimal", "2"))).isEqualTo("7.00");
    assertThatThrownBy(() -> run("2026-12-31", t("toDate", "\"dd/MM/yyyy\"")))
        .isInstanceOf(FieldTransformer.TransformException.class)
        .hasMessageNotContaining("2026");
    assertThatThrownBy(() -> run("1,5", t("toDecimal", "2")))
        .isInstanceOf(FieldTransformer.TransformException.class);
  }

  @Test
  void defaultAppliesToNullAndEmptyOnly() {
    assertThat(run(null, t("default", "\"ABERTO\""))).isEqualTo("ABERTO");
    assertThat(run("", t("default", "5"))).isEqualTo("5");
    assertThat(run("PAGO", t("default", "\"ABERTO\""))).isEqualTo("PAGO");
    assertThat(run(null, t("default", "true"))).isEqualTo("true");
  }

  @Test
  void mapConvertsKnownValuesAndRejectsOthers() {
    Transform map = t("map", "{\"A\":\"ATIVO\",\"I\":0}");
    assertThat(run("A", map)).isEqualTo("ATIVO");
    assertThat(run("I", map)).isEqualTo("0");
    assertThatThrownBy(() -> run("X", map))
        .isInstanceOf(FieldTransformer.TransformException.class)
        .hasMessageNotContaining("X");
  }

  @Test
  void nullsPassThroughOtherFunctions() {
    assertThat(run(null, t("trim"), t("upper"), t("digits"))).isNull();
    assertThat(run(null, t("map", "{\"A\":\"B\"}"))).isNull();
  }
}
