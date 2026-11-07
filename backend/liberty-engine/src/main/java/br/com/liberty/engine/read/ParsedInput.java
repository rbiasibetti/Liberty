package br.com.liberty.engine.read;

import java.util.Map;

/** Entrada de uma leitura já conferida: valores declarados (texto), página e ordenação pedidas. */
public record ParsedInput(Map<String, String> values, int limit, int offset, String sort) {

  public ParsedInput {
    values = Map.copyOf(values);
  }
}
