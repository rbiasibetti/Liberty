package br.com.liberty.domain.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ab",
        "cliente-a",
        "0cliente",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      })
  void acceptsValidSlugs(String slug) {
    assertThat(new TenantId(slug).value()).isEqualTo(slug);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "a",
        "-ab",
        "Cliente",
        "cli ente",
        "cli_ente",
        "a/b",
        "a;b",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      })
  void rejectsInvalidSlugs(String slug) {
    assertThatThrownBy(() -> new TenantId(slug)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNull() {
    assertThatNullPointerException().isThrownBy(() -> new TenantId(null));
  }

  @Test
  void printsOnlyTheSlug() {
    assertThat(new TenantId("cliente-a")).hasToString("cliente-a");
  }
}
