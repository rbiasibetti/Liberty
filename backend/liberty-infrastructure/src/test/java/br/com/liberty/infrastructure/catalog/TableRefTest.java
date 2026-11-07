package br.com.liberty.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TableRefTest {

  @ParameterizedTest
  @ValueSource(strings = {"tb_cliente", "vendas.produto", "_x", "A1", "dbo.T"})
  void acceptsPlainIdentifiers(String reference) {
    assertThat(TableRef.parse(reference)).isNotNull();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "tb cliente",
        "tb-cliente",
        "1abc",
        "[tb_cliente]",
        "\"tb_cliente\"",
        "'tb_cliente'",
        "tb_cliente;",
        "tb_cliente; DROP TABLE tb_cliente;--",
        "x]; DROP TABLE tb_cliente;--",
        "tb_cliente--",
        "tb_cliente/*x*/",
        "tb_cliente\n",
        "tb_cliente\u0000",
        "tb_cliente ",
        " tb_cliente",
        "a.b.c",
        ".tb",
        "tb.",
        "master..sysdatabases",
        "linked.db.dbo.tb",
        "tb_clienté",
        "tb_cliente‮",
        "ｔｂ_cliente",
        "tb%",
        "tb_%",
        "dbo.tb*",
        "tb\\_x",
        "@@version",
        "#temp",
        "##global",
        "tb_cliente WHERE 1=1",
        "tb_cliente,tb_pedido",
        "tb_cliente(id)"
      })
  void rejectsEverythingElse(String reference) {
    assertThatThrownBy(() -> TableRef.parse(reference))
        .isInstanceOf(InvalidIdentifierException.class);
  }

  @org.junit.jupiter.api.Test
  void unqualifiedMeansDboAndLengthIsBounded() {
    assertThat(TableRef.parse("tb_cliente")).isEqualTo(new TableRef("dbo", "tb_cliente"));
    assertThat(TableRef.parse("a".repeat(128))).isNotNull();
    assertThatThrownBy(() -> TableRef.parse("a".repeat(129)))
        .isInstanceOf(InvalidIdentifierException.class);
    assertThat(TableRef.parse("Vendas.Produto").normalized())
        .isEqualTo(new TableRef("vendas", "produto"));
    assertThat(TableRef.parse("vendas.produto")).hasToString("vendas.produto");
  }
}
