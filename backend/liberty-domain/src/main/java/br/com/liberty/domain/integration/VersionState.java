package br.com.liberty.domain.integration;

/** Estado de uma versão de integração: só sai do rascunho uma vez e depois é imutável. */
public enum VersionState {
  DRAFT,
  IN_REVIEW,
  PUBLISHED,
  REJECTED;

  /**
   * Transições permitidas; as mesmas são impostas no banco pelo gatilho `trg_int_version_guard`.
   */
  public boolean canTransitionTo(VersionState next) {
    return switch (this) {
      case DRAFT -> next == DRAFT || next == IN_REVIEW;
      case IN_REVIEW -> next == IN_REVIEW || next == PUBLISHED || next == REJECTED;
      case PUBLISHED, REJECTED -> next == this;
    };
  }
}
