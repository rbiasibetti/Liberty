package br.com.liberty.engine.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteTableTest {

  private static PublishedIntegration integration(
      long id, String key, String method, String route) {
    return new PublishedIntegration(id, key, method, id, 1, null, RoutePattern.parse(route));
  }

  @Test
  void matchesLiteralsAndVariables() {
    var pattern = RoutePattern.parse("/clientes/{id}/pedidos");

    assertThat(pattern.match(List.of("clientes", "42", "pedidos"))).contains(Map.of("id", "42"));
    assertThat(pattern.match(List.of("clientes", "42"))).isEmpty();
    assertThat(pattern.match(List.of("clientes", "42", "pedidos", "x"))).isEmpty();
    assertThat(pattern.match(List.of("outros", "42", "pedidos"))).isEmpty();
    assertThat(pattern.match(List.of("Clientes", "42", "pedidos")))
        .as("literais diferenciam maiúsculas")
        .isEmpty();
    assertThat(pattern.signature()).isEqualTo("/clientes/{}/pedidos");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "/", "pedidos", "//a", "/a//b", "/a/"})
  void rejectsMalformedRoutes(String route) {
    assertThatIllegalArgumentException().isThrownBy(() -> RoutePattern.parse(route));
  }

  @Test
  void literalBeatsVariableRegardlessOfRegistrationOrder() {
    var table =
        RouteTable.of(
            List.of(
                integration(1, "por-numero", "GET", "/pedidos/{numero}"),
                integration(2, "resumo", "GET", "/pedidos/resumo")));

    assertThat(
            table
                .resolve("GET", List.of("pedidos", "resumo"))
                .match()
                .orElseThrow()
                .integration()
                .key())
        .isEqualTo("resumo");
    assertThat(
            table
                .resolve("GET", List.of("pedidos", "P-1"))
                .match()
                .orElseThrow()
                .integration()
                .key())
        .isEqualTo("por-numero");
    assertThat(
            table.resolve("GET", List.of("pedidos", "P-1")).match().orElseThrow().pathVariables())
        .containsEntry("numero", "P-1");
  }

  @Test
  void literalWinsInTheEarliestSegment() {
    var table =
        RouteTable.of(
            List.of(
                integration(1, "a", "GET", "/{x}/fixo"), integration(2, "b", "GET", "/fixo/{y}")));

    assertThat(
            table.resolve("GET", List.of("fixo", "fixo")).match().orElseThrow().integration().key())
        .isEqualTo("b");
  }

  @Test
  void wrongMethodGivesTheAllowedMethodsAndUnknownPathsGiveNothing() {
    var table =
        RouteTable.of(
            List.of(
                integration(1, "ler", "GET", "/pedidos"),
                integration(2, "criar", "POST", "/pedidos")));

    var wrong = table.resolve("DELETE", List.of("pedidos"));
    var unknown = table.resolve("GET", List.of("nada"));

    assertThat(wrong.match()).isEmpty();
    assertThat(wrong.allowedMethods()).containsExactlyInAnyOrder("GET", "POST");
    assertThat(wrong.notFound()).isFalse();
    assertThat(unknown.notFound()).isTrue();
    assertThat(table.resolve("GET", List.of()).notFound()).isTrue();
  }

  @Test
  void structurallyEqualRoutesAreAmbiguousAndOnlyTheOldestIsServed() {
    var table =
        RouteTable.of(
            List.of(
                integration(7, "novo", "GET", "/itens/{b}"),
                integration(3, "antigo", "GET", "/itens/{a}"),
                integration(9, "outro-metodo", "POST", "/itens/{c}")));

    assertThat(table.size()).isEqualTo(2);
    assertThat(
            table.resolve("GET", List.of("itens", "1")).match().orElseThrow().integration().key())
        .isEqualTo("antigo");
    assertThat(table.resolve("POST", List.of("itens", "1")).match()).isPresent();
  }

  @Test
  void emptyTableServesNothing() {
    assertThat(RouteTable.empty().resolve("GET", List.of("a")).notFound()).isTrue();
  }
}
