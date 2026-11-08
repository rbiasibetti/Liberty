package br.com.liberty.engine.queue;

import br.com.liberty.engine.outbound.OutboundHttp;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fila {@code webhook}: entrega uma chamada HTTP ao sistema do cliente, pelo mesmo {@code
 * http.fetch} (HTTPS, lista de hosts do tenant, só endereços públicos). Conteúdo: {@code {url,
 * method?, headers?, body?}}. 2xx conclui; 408, 425, 429, 5xx e falhas de rede tentam de novo;
 * qualquer outra resposta e toda recusa de política vão direto para as mortas. A mensagem leva
 * {@code X-Liberty-Message-Id} (idêntico nas reentregas) para o destino descartar duplicatas.
 */
public final class WebhookHandler implements QueueHandler {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final OutboundHttp outbound;

  public WebhookHandler(OutboundHttp outbound) {
    this.outbound = outbound;
  }

  @Override
  public String queue() {
    return "webhook";
  }

  @Override
  public void handle(QueueEnvelope message) {
    JsonNode payload = message.payload();
    String url = payload.path("url").asString("");
    String method = payload.path("method").asString("POST");
    Map<String, String> headers = new LinkedHashMap<>();
    JsonNode given = payload.path("headers");
    if (given.isObject()) {
      for (var entry : given.properties()) {
        headers.put(entry.getKey(), entry.getValue().asString(""));
      }
    }
    headers.put("X-Liberty-Message-Id", message.tenant() + "-" + message.id());
    headers.put("X-Liberty-Attempt", Integer.toString(message.attempt()));
    JsonNode body = payload.get("body");
    String bodyJson =
        body == null || body.isNull()
            ? "null"
            : MAPPER.writeValueAsString(
                body.isString() ? body.asString() : MAPPER.writeValueAsString(body));
    if (body != null && !body.isNull() && !body.isString()) {
      headers.putIfAbsent("Content-Type", "application/json");
    }
    String result;
    try {
      result =
          outbound
              .session(message.tenant(), false)
              .fetch(method, url, MAPPER.writeValueAsString(headers), bodyJson);
    } catch (OutboundHttp.OutboundDenied e) {
      if (e.transientFailure()) {
        throw new IllegalStateException(e.getMessage(), e);
      }
      throw new Permanent(e.getMessage());
    }
    int status = MAPPER.readTree(result).path("status").asInt();
    if (status >= 200 && status < 300) {
      return;
    }
    if (status == 408 || status == 425 || status == 429 || status >= 500) {
      throw new IllegalStateException("O destino respondeu " + status);
    }
    throw new Permanent("O destino recusou com " + status);
  }
}
