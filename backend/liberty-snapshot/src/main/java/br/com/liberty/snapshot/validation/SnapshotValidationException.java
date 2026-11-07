package br.com.liberty.snapshot.validation;

import java.util.List;

/** O snapshot não é válido; {@link #errors()} traz um item por campo defeituoso. */
public class SnapshotValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<FieldError> errors;

  public SnapshotValidationException(List<FieldError> errors) {
    super("Snapshot inválido: " + errors.size() + " erro(s)");
    this.errors = List.copyOf(errors);
  }

  public List<FieldError> errors() {
    return errors;
  }
}
