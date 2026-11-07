package br.com.liberty.snapshot.validation;

import java.util.Comparator;

/**
 * Um defeito localizado: {@code path} é um JSON Pointer para o campo
 * ("/operation/write/root/fields/2/column"), {@code code} um identificador estável para a interface
 * e os testes, {@code message} o texto para a pessoa.
 */
public record FieldError(String path, String code, String message)
    implements Comparable<FieldError> {

  private static final Comparator<FieldError> ORDER =
      Comparator.comparing(FieldError::path)
          .thenComparing(FieldError::code)
          .thenComparing(FieldError::message);

  @Override
  public int compareTo(FieldError other) {
    return ORDER.compare(this, other);
  }
}
