package br.com.liberty.engine.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Peças das exportações que não precisam de banco: URL assinada, CSV e armazenamento em disco. */
class ExportsUnitTest {

  private static final byte[] KEY =
      "uma-chave-de-teste-com-mais-de-32-bytes!".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

  private static ExportLinks links(Instant now) {
    return new ExportLinks(KEY, Clock.fixed(now, ZoneOffset.UTC));
  }

  // ---- URL assinada ---------------------------------------------------------------------

  @Test
  void aSignedLinkCarriesTenantJobAndExpiryAndExpiresOnItsOwn() {
    String token = links(NOW).sign("acme", 42, NOW.plusSeconds(600));

    var claims = links(NOW.plusSeconds(599)).verify(token).orElseThrow();
    assertThat(claims.tenant()).isEqualTo("acme");
    assertThat(claims.jobId()).isEqualTo(42);
    assertThat(claims.expires()).isEqualTo(NOW.plusSeconds(600));
    assertThat(links(NOW.plusSeconds(600)).verify(token)).as("no instante do vencimento").isEmpty();
    assertThat(links(NOW.plusSeconds(601)).verify(token)).isEmpty();
  }

  @Test
  void tamperedForgedAndForeignTokensAreRejected() {
    ExportLinks real = links(NOW);
    String token = real.sign("acme", 42, NOW.plusSeconds(600));
    String payload = token.substring(0, token.indexOf('.'));
    String signature = token.substring(token.indexOf('.') + 1);
    String otherPayload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("v1|beta|42|" + NOW.plusSeconds(600).getEpochSecond())
                    .getBytes(StandardCharsets.UTF_8));
    ExportLinks otherKey =
        new ExportLinks(
            "outra-chave-de-teste-com-32-bytes-ou-mais!!".getBytes(StandardCharsets.UTF_8),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(real.verify(otherPayload + "." + signature)).as("trocou o tenant").isEmpty();
    assertThat(real.verify(payload + "." + signature.substring(1))).isEmpty();
    assertThat(real.verify(payload)).isEmpty();
    assertThat(real.verify(payload + "." + signature + ".x")).isEmpty();
    assertThat(real.verify("")).isEmpty();
    assertThat(real.verify(null)).isEmpty();
    assertThat(real.verify("a".repeat(600))).isEmpty();
    assertThat(real.verify("!!!.@@@")).isEmpty();
    assertThat(otherKey.verify(token)).as("outra chave").isEmpty();
    assertThatThrownBy(
            () -> new ExportLinks("curta".getBytes(StandardCharsets.UTF_8), Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- CSV ------------------------------------------------------------------------------

  @Test
  void csvCellsAreQuotedAndFormulaInjectionIsNeutralized() {
    assertThat(CsvWriter.cell(null, false)).isEmpty();
    assertThat(CsvWriter.cell("simples", false)).isEqualTo("simples");
    assertThat(CsvWriter.cell("a,b", false)).isEqualTo("\"a,b\"");
    assertThat(CsvWriter.cell("diz \"oi\"", false)).isEqualTo("\"diz \"\"oi\"\"\"");
    assertThat(CsvWriter.cell("linha1\nlinha2", false)).isEqualTo("\"linha1\nlinha2\"");
    for (String formula : List.of("=1+1", "+1", "-1", "@SOMA(A1)", "\tx", "\rx")) {
      assertThat(CsvWriter.cell(formula, false)).as(formula).contains("'" + formula);
    }
    assertThat(CsvWriter.cell(-5, false)).as("número não é texto").isEqualTo("-5");
    assertThat(CsvWriter.cell(new BigDecimal("-1.50"), false)).isEqualTo("-1.50");
    assertThat(CsvWriter.cell(new BigDecimal("1E+3"), false)).isEqualTo("1000");
    assertThat(CsvWriter.cell(true, false)).isEqualTo("true");
    assertThat(CsvWriter.cell("-x", true)).as("nome de coluna também").isEqualTo("'-x");
    assertThat(CsvWriter.cell("", false)).isEmpty();
  }

  @Test
  void csvWritesBomHeaderRowsAndStopsAtTheSizeLimit() throws Exception {
    var out = new ByteArrayOutputStream();
    try (var csv = new CsvWriter(out, 1000)) {
      csv.header(List.of("a", "b"));
      var row = new LinkedHashMap<String, Object>();
      row.put("a", "x");
      row.put("b", 2);
      csv.row(row, List.of("a", "b"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("﻿a,b\r\nx,2\r\n");

    var small = new ByteArrayOutputStream();
    try (var csv = new CsvWriter(small, 10)) {
      assertThatThrownBy(() -> csv.header(List.of("coluna-muito-comprida")))
          .isInstanceOf(CsvWriter.TooLarge.class);
    }
  }

  // ---- armazenamento --------------------------------------------------------------------

  @Test
  void theLocalStoreOnlyExposesCommittedObjectsAndRefusesPathTricks() throws Exception {
    var root = Files.createTempDirectory("liberty-store-unit");
    var store = new LocalObjectStore(root);

    try (var upload = store.begin("1/2.csv")) {
      upload.stream().write("parcial".getBytes(StandardCharsets.UTF_8));
    }
    assertThatThrownBy(() -> store.open("1/2.csv"))
        .as("sem commit, não existe")
        .isInstanceOf(java.io.IOException.class);

    try (var upload = store.begin("1/2.csv")) {
      upload.stream().write("inteiro".getBytes(StandardCharsets.UTF_8));
      upload.commit();
    }
    try (var in = store.open("1/2.csv")) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("inteiro");
    }
    store.delete("1/2.csv");
    store.delete("1/2.csv");
    assertThatThrownBy(() -> store.open("1/2.csv")).isInstanceOf(java.io.IOException.class);
    for (String bad :
        List.of(
            "../x.csv",
            "1/../../x.csv",
            "/etc/passwd",
            "1/2.csv/../3.csv",
            "a/b.csv",
            "1/2",
            "1/2.CSV",
            "")) {
      assertThatThrownBy(() -> store.begin(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> store.open(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    try (var files = Files.walk(root)) {
      assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count()).isZero();
    }
  }
}
