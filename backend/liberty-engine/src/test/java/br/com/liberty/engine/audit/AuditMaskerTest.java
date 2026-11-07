package br.com.liberty.engine.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditMaskerTest {

  @Test
  void masksEmailsDocumentsTokensAndAuthorizationHeaders() {
    String masked =
        AuditMasker.mask(
            "Falha para ana.silva@exemplo.com.br CPF 123.456.789-09 cartão 4111 1111 1111 1111 "
                + "Authorization: Bearer abc.DEF-123_xyz token eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9abcdef");

    assertThat(masked)
        .doesNotContain("ana.silva", "123.456", "4111", "abc.DEF", "eyJhbGci")
        .contains("***@***")
        .contains("Bearer ***");
  }

  @Test
  void keepsHarmlessTextAndHandlesNullsAndLimits() {
    assertThat(AuditMasker.mask("Valor inválido para o campo"))
        .isEqualTo("Valor inválido para o campo");
    assertThat(AuditMasker.mask("Pedido 42 com 3 itens")).isEqualTo("Pedido 42 com 3 itens");
    assertThat(AuditMasker.mask(null)).isNull();
    assertThat(AuditMasker.mask("x".repeat(1000)).length())
        .isLessThanOrEqualTo(AuditMasker.MAX_LENGTH);
    assertThat(AuditMasker.mask("linha1\nlinha2\u0000"))
        .doesNotContain("\n")
        .doesNotContain("\u0000");
  }

  @Test
  void requestTimingsSumPerThreadAndCloseCleanly() {
    RequestTimings.addDb(5_000_000); // fora de begin/end: ignorado
    RequestTimings.begin();
    RequestTimings.addDb(3_000_000);
    RequestTimings.addDb(2_000_000);
    RequestTimings.addScript(7_000_000);
    assertThat(RequestTimings.scriptNanosNow()).isEqualTo(7_000_000);

    var totals = RequestTimings.end();

    assertThat(totals.dbMillis()).isEqualTo(5);
    assertThat(totals.scriptMillis()).isEqualTo(7);
    assertThat(RequestTimings.end().dbMillis()).isZero();
    assertThat(RequestTimings.scriptNanosNow()).isZero();
  }
}
