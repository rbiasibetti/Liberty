package br.com.liberty.domain.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

  private static final TenantScope A =
      new TenantScope(new TenantId("cliente-a"), EnvironmentKind.HML);
  private static final TenantScope B =
      new TenantScope(new TenantId("cliente-b"), EnvironmentKind.PRD);

  @AfterEach
  void clean() {
    TenantContext.clear();
  }

  @Test
  void requireFailsWithoutTenantAndThereIsNoDefault() {
    assertThat(TenantContext.current()).isEmpty();
    assertThatIllegalStateException().isThrownBy(TenantContext::require);
  }

  @Test
  void setAndClear() {
    TenantContext.set(A);

    assertThat(TenantContext.require()).isEqualTo(A);
    assertThat(A).hasToString("cliente-a/HML");

    TenantContext.clear();
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void nestedScopesRestoreThePreviousOne() throws Exception {
    TenantContext.set(A);

    String inner = TenantContext.callAs(B, () -> TenantContext.require().toString());

    assertThat(inner).isEqualTo("cliente-b/PRD");
    assertThat(TenantContext.require()).isEqualTo(A);
  }

  @Test
  void contextIsRestoredEvenWhenTheActionFails() {
    assertThatThrownBy(
            () ->
                TenantContext.supplyAs(
                    A,
                    () -> {
                      throw new IllegalArgumentException("x");
                    }))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void runAsAndRunWithoutTenant() {
    List<String> seen = new ArrayList<>();
    TenantContext.runAs(
        A,
        () ->
            TenantContext.runWithoutTenant(
                () -> seen.add(String.valueOf(TenantContext.current().isPresent()))));

    assertThat(seen).containsExactly("false");
    assertThat(TenantContext.current()).isEmpty();
  }

  @Test
  void scopeRequiresBothParts() {
    assertThatNullPointerException().isThrownBy(() -> new TenantScope(null, EnvironmentKind.HML));
    assertThatNullPointerException().isThrownBy(() -> new TenantScope(new TenantId("ab"), null));
    assertThatNullPointerException().isThrownBy(() -> TenantContext.set(null));
  }

  @Test
  void threadsDoNotSeeEachOthersTenant() throws Exception {
    TenantContext.set(A);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> other = pool.submit(() -> TenantContext.current().isEmpty());

      assertThat(other.get()).isTrue();
    }
  }
}
