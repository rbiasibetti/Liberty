package br.com.liberty.domain.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnvironmentKindTest {

  @Test
  void hasExactlyHomologationAndProduction() {
    assertThat(EnvironmentKind.values()).containsExactly(EnvironmentKind.HML, EnvironmentKind.PRD);
    assertThat(EnvironmentKind.valueOf("PRD")).isSameAs(EnvironmentKind.PRD);
  }
}
