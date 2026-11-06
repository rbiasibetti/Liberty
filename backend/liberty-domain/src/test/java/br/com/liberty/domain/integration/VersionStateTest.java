package br.com.liberty.domain.integration;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.tenancy.TenantRole;
import org.junit.jupiter.api.Test;

class VersionStateTest {

  @Test
  void followsDraftReviewPublishedOrRejected() {
    assertThat(VersionState.DRAFT.canTransitionTo(VersionState.IN_REVIEW)).isTrue();
    assertThat(VersionState.IN_REVIEW.canTransitionTo(VersionState.PUBLISHED)).isTrue();
    assertThat(VersionState.IN_REVIEW.canTransitionTo(VersionState.REJECTED)).isTrue();
  }

  @Test
  void rejectsShortcutsAndReopening() {
    assertThat(VersionState.DRAFT.canTransitionTo(VersionState.PUBLISHED)).isFalse();
    assertThat(VersionState.IN_REVIEW.canTransitionTo(VersionState.DRAFT)).isFalse();
    assertThat(VersionState.PUBLISHED.canTransitionTo(VersionState.DRAFT)).isFalse();
    assertThat(VersionState.REJECTED.canTransitionTo(VersionState.PUBLISHED)).isFalse();
  }

  @Test
  void keepingTheSameStateIsAlwaysAllowed() {
    for (VersionState state : VersionState.values()) {
      assertThat(state.canTransitionTo(state)).isTrue();
    }
  }

  @Test
  void hasTheThreeTenantRoles() {
    assertThat(TenantRole.values())
        .containsExactly(TenantRole.VIEWER, TenantRole.INTEGRATOR, TenantRole.PUBLISHER);
  }
}
