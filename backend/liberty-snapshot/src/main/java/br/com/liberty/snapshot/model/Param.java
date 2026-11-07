package br.com.liberty.snapshot.model;

/** Parâmetro de caminho ou de query; os de caminho são sempre obrigatórios. */
public record Param(String name, String in, String type, Boolean required, Integer maxLength) {

  public boolean isRequired() {
    return "path".equals(in) || Boolean.TRUE.equals(required);
  }
}
