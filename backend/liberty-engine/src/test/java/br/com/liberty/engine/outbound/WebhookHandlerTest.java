package br.com.liberty.engine.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.engine.queue.QueueEnvelope;
import br.com.liberty.engine.queue.QueueHandler;
import br.com.liberty.engine.queue.WebhookHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fila {@code webhook} passa pelo mesmo {@code http.fetch}: política, destino e classificação de
 * falhas.
 */
class WebhookHandlerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static HttpServer server;
  private static int port;
  private static OutboundHttp outbound;
  private static WebhookHandler handler;
  private static final CopyOnWriteArrayList<String> RECEIVED = new CopyOnWriteArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/",
        ex -> {
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          RECEIVED.add(
              ex.getRequestMethod()
                  + " "
                  + ex.getRequestURI().getPath()
                  + " id="
                  + ex.getRequestHeaders().getFirst("X-Liberty-Message-Id")
                  + " attempt="
                  + ex.getRequestHeaders().getFirst("X-Liberty-Attempt")
                  + " type="
                  + ex.getRequestHeaders().getFirst("Content-Type")
                  + " body="
                  + body);
          int status = Integer.parseInt(ex.getRequestURI().getPath().substring(1));
          ex.sendResponseHeaders(status, -1);
          ex.close();
        });
    server.start();
    var allowed = ConcurrentHashMap.<String>newKeySet();
    allowed.add("clienta|cliente.test:" + port);
    DnsResolver dns =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String host) throws UnknownHostException {
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
          }

          @Override
          public String resolveCanonicalHostname(String host) {
            return host;
          }
        };
    outbound =
        new OutboundHttp(
            new OutboundProperties(Duration.ofSeconds(2), Duration.ofSeconds(1), 1024, 1024, 3, 4),
            (tenant, host) -> allowed.contains(tenant + "|" + host),
            dns,
            address -> true,
            true);
    handler = new WebhookHandler(outbound);
  }

  @AfterAll
  static void stop() throws Exception {
    server.stop(0);
    outbound.close();
  }

  private static QueueEnvelope message(String tenant, String payload) {
    return new QueueEnvelope(
        42, tenant, EnvironmentKind.PRD, "webhook", 2, null, Instant.now(), JSON.readTree(payload));
  }

  private static String url(int status) {
    return "http://cliente.test:" + port + "/" + status;
  }

  @Test
  void aSuccessfulDeliveryCarriesTheStableMessageIdAndAJsonBody() {
    handler.handle(
        message(
            "clienta",
            "{\"url\":\"" + url(204) + "\",\"body\":{\"pedido\":7},\"headers\":{\"X-A\":\"1\"}}"));

    assertThat(RECEIVED.getLast())
        .contains(
            "POST /204", "id=clienta-42", "attempt=2", "type=application/json", "{\"pedido\":7}");
  }

  @Test
  void temporaryFailuresRetryAndOtherRefusalsAreFinal() {
    for (int status : new int[] {500, 502, 503, 429, 408, 425}) {
      assertThatThrownBy(
              () -> handler.handle(message("clienta", "{\"url\":\"" + url(status) + "\"}")))
          .as(String.valueOf(status))
          .isInstanceOf(IllegalStateException.class)
          .isNotInstanceOf(QueueHandler.Permanent.class);
    }
    for (int status : new int[] {400, 401, 403, 404, 410, 302}) {
      assertThatThrownBy(
              () -> handler.handle(message("clienta", "{\"url\":\"" + url(status) + "\"}")))
          .as(String.valueOf(status))
          .isInstanceOf(QueueHandler.Permanent.class);
    }
  }

  @Test
  void policyRefusalsAreFinalButNetworkFailuresRetry() {
    assertThatThrownBy(
            () -> handler.handle(message("clienta", "{\"url\":\"https://outro.exemplo.com/\"}")))
        .as("host não liberado")
        .isInstanceOf(QueueHandler.Permanent.class)
        .hasMessageContaining("não liberado");
    assertThatThrownBy(() -> handler.handle(message("clientb", "{\"url\":\"" + url(200) + "\"}")))
        .as("o host liberado para A não vale para B")
        .isInstanceOf(QueueHandler.Permanent.class);
    assertThatThrownBy(() -> handler.handle(message("clienta", "{\"url\":\"https://127.0.0.1/\"}")))
        .as("IP")
        .isInstanceOf(QueueHandler.Permanent.class);
    assertThatThrownBy(() -> handler.handle(message("clienta", "{}")))
        .as("sem URL")
        .isInstanceOf(QueueHandler.Permanent.class);
    assertThatThrownBy(
            () ->
                handler.handle(
                    message("clienta", "{\"url\":\"" + url(200) + "\",\"method\":\"TRACE\"}")))
        .isInstanceOf(QueueHandler.Permanent.class);
  }
}
