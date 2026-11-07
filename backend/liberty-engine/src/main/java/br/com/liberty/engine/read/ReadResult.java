package br.com.liberty.engine.read;

import java.util.List;
import java.util.Map;

/** Resposta de uma leitura: o corpo (lista paginada ou o único objeto) já no formato de saída. */
public record ReadResult(Object body) {

  public static ReadResult page(
      List<Map<String, Object>> items, int limit, int offset, boolean hasMore) {
    Map<String, Object> page = new java.util.LinkedHashMap<>();
    page.put("limit", limit);
    page.put("offset", offset);
    page.put("returned", items.size());
    page.put("hasMore", hasMore);
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("items", items);
    body.put("page", page);
    return new ReadResult(body);
  }

  public static ReadResult single(Map<String, Object> item) {
    return new ReadResult(item);
  }
}
