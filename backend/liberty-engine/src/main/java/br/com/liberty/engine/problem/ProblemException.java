package br.com.liberty.engine.problem;

import java.util.List;

/**
 * Falha com resposta HTTP definida (RFC 9457). {@code type} é um identificador estável
 * (urn:liberty:problem:...) e {@code violations} lista os campos ou parâmetros defeituosos, um por
 * item.
 */
public class ProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Um defeito localizado em um parâmetro ou campo. */
  public record Violation(String parameter, String code, String message) {}

  private final int status;
  private final String type;
  private final String title;
  private final transient List<Violation> violations;
  private final transient java.util.Map<String, String> headers;

  public ProblemException(
      int status, String type, String title, String detail, List<Violation> violations) {
    this(status, type, title, detail, violations, java.util.Map.of());
  }

  public ProblemException(
      int status,
      String type,
      String title,
      String detail,
      List<Violation> violations,
      java.util.Map<String, String> headers) {
    super(detail);
    this.status = status;
    this.type = "urn:liberty:problem:" + type;
    this.title = title;
    this.violations = List.copyOf(violations);
    this.headers = java.util.Map.copyOf(headers);
  }

  public ProblemException(int status, String type, String title, String detail) {
    this(status, type, title, detail, List.of());
  }

  public static ProblemException validation(List<Violation> violations) {
    return new ProblemException(
        400,
        "validation",
        "Requisição inválida",
        "Um ou mais parâmetros são inválidos",
        violations);
  }

  /** Cabeçalhos que a resposta deve levar (ex.: Allow no 405). */
  public java.util.Map<String, String> headers() {
    return headers;
  }

  public int status() {
    return status;
  }

  public String type() {
    return type;
  }

  public String title() {
    return title;
  }

  public List<Violation> violations() {
    return violations;
  }
}
