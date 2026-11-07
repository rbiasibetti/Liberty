package br.com.liberty.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

  private static String canonical(String json) {
    return CanonicalJson.write(Fixtures.MAPPER.readTree(json));
  }

  @Test
  void sortsObjectKeysAtEveryLevelAndKeepsArrayOrder() {
    assertThat(canonical("{\"b\":{\"z\":1,\"a\":[3,1,2]},\"a\":true}"))
        .isEqualTo("{\"a\":true,\"b\":{\"a\":[3,1,2],\"z\":1}}");
  }

  @Test
  void normalizesNumbers() {
    assertThat(
            canonical("[1, 1.0, 1.50, 0.10, -0, 0.0, 1e2, 1E-7, 123456789012345678901234567890]"))
        .isEqualTo("[1,1,1.5,0.1,0,0,100,0.0000001,123456789012345678901234567890]");
  }

  @Test
  void writesStringsWithMinimalEscaping() {
    assertThat(canonical("[\"a\\\"b\", \"é中\", \"\\n\\t\", \"\\u0001\", \"/\"]"))
        .isEqualTo("[\"a\\\"b\",\"é中\",\"\\n\\t\",\"\\u0001\",\"/\"]");
  }

  @Test
  void writesNullsAndBooleans() {
    assertThat(canonical("{\"n\":null,\"t\":true,\"f\":false}"))
        .isEqualTo("{\"f\":false,\"n\":null,\"t\":true}");
  }

  @Test
  void hashMatchesAKnownSha256() {
    String expected = "eca8cfb31ab74533e1eb2f4c74d2d55dfe3c79ac704787e54be8647ea7777eb1";

    assertThat(
            HexFormat.of()
                .formatHex(
                    CanonicalJson.sha256(
                        Fixtures.MAPPER.readTree("{ \"b\": [true, null, \"x\"],\n \"a\": 1.0 }"))))
        .isEqualTo(expected);
  }
}
