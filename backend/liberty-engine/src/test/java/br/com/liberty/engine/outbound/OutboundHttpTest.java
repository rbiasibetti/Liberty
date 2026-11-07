package br.com.liberty.engine.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Suíte de SSRF do {@code http.fetch} (critério de saída da E22): endereços, formas de URL, lista
 * de permissão por tenant, DNS que aponta para dentro, redirecionamentos, limites e sandbox.
 */
class OutboundHttpTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final OutboundProperties PROPS =
      new OutboundProperties(Duration.ofSeconds(2), Duration.ofSeconds(1), 1024, 1024, 3, 8);

  private static HttpServer server;
  private static int port;
  private static final AtomicInteger HITS = new AtomicInteger();
  private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();
  private static final Map<String, List<String>> DNS = new ConcurrentHashMap<>();

  private static final HostAllowList ALLOW_LIST =
      (tenant, host) -> ALLOWED.contains(tenant + "|" + host);

  private static final DnsResolver STUB_DNS =
      new DnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
          List<String> ips = DNS.get(host);
          if (ips == null) {
            throw new UnknownHostException(host);
          }
          InetAddress[] out = new InetAddress[ips.size()];
          for (int i = 0; i < out.length; i++) {
            out[i] = InetAddress.getByName(ips.get(i));
          }
          return out;
        }

        @Override
        public String resolveCanonicalHostname(String host) {
          return host;
        }
      };

  private static OutboundHttp strict;
  private static OutboundHttp open;

  @BeforeAll
  static void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/eco",
        ex -> {
          HITS.incrementAndGet();
          byte[] in = ex.getRequestBody().readAllBytes();
          String text =
              ex.getRequestMethod()
                  + "|"
                  + new String(in, StandardCharsets.UTF_8)
                  + "|"
                  + ex.getRequestHeaders().getFirst("X-Teste")
                  + "|"
                  + ex.getRequestHeaders().getFirst("Content-Type")
                  + "|"
                  + ex.getRequestHeaders().getFirst("User-Agent")
                  + "|"
                  + ex.getRequestHeaders().getFirst("Cookie")
                  + "|"
                  + ex.getRequestHeaders().getFirst("Accept-Encoding");
          byte[] out = text.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("X-Resposta", "ok");
          ex.getResponseHeaders().add("Set-Cookie", "segredo=1");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.createContext(
        "/redireciona",
        ex -> {
          HITS.incrementAndGet();
          ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
          ex.sendResponseHeaders(302, -1);
          ex.close();
        });
    server.createContext(
        "/grande",
        ex -> {
          HITS.incrementAndGet();
          ex.sendResponseHeaders(200, 0); // em pedaços, sem Content-Length
          ex.getResponseBody().write(new byte[4096]);
          ex.close();
        });
    server.createContext(
        "/grande-declarado",
        ex -> {
          HITS.incrementAndGet();
          ex.sendResponseHeaders(200, 4096);
          ex.getResponseBody().write(new byte[4096]);
          ex.close();
        });
    server.createContext(
        "/lento",
        ex -> {
          HITS.incrementAndGet();
          try {
            Thread.sleep(4000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.start();
    strict = new OutboundHttp(PROPS, ALLOW_LIST, STUB_DNS, AddressPolicy.PUBLIC_ONLY, false);
    open = new OutboundHttp(PROPS, ALLOW_LIST, STUB_DNS, address -> true, true);
    ALLOWED.add("acme|api.exemplo.com");
    ALLOWED.add("acme|evil.exemplo.com");
    ALLOWED.add("acme|api.test:" + port);
    DNS.put("api.test", List.of("127.0.0.1"));
    DNS.put("api.exemplo.com", List.of("93.184.216.34"));
  }

  @AfterAll
  static void stop() throws Exception {
    server.stop(0);
    strict.close();
    open.close();
  }

  private static String fetch(OutboundHttp http, String url) {
    return http.session("acme", false).fetch("GET", url, "{}", "null");
  }

  private static JsonNode ok(String json) {
    return JSON.readTree(json);
  }

  // ---- endereços ----------------------------------------------------------------------

  @Test
  void onlyPublicAddressesPassThePolicy() throws Exception {
    List<String> denied =
        List.of(
            "127.0.0.1",
            "127.255.255.254",
            "0.0.0.0",
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "100.127.255.255",
            "224.0.0.1",
            "239.255.255.250",
            "240.0.0.1",
            "255.255.255.255",
            "192.0.0.1",
            "192.0.2.5",
            "198.18.0.1",
            "198.51.100.7",
            "203.0.113.9",
            "::1",
            "::",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.1",
            "::127.0.0.1",
            "64:ff9b::7f00:1",
            "2002:7f00:1::",
            "2001:db8::1");
    for (String ip : denied) {
      assertThat(AddressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName(ip))).as(ip).isFalse();
    }
    for (String ip :
        List.of(
            "8.8.8.8",
            "1.1.1.1",
            "93.184.216.34",
            "172.32.0.1",
            "100.128.0.1",
            "2606:4700:4700::1111")) {
      assertThat(AddressPolicy.PUBLIC_ONLY.allows(InetAddress.getByName(ip))).as(ip).isTrue();
    }
  }

  // ---- formas de URL ------------------------------------------------------------------

  @Test
  void malformedAndDangerousUrlsAreRefusedBeforeAnyNetworkAccess() {
    int before = HITS.get();
    List<String> urls =
        List.of(
            "file:///etc/passwd",
            "ftp://api.exemplo.com/",
            "gopher://api.exemplo.com/",
            "jar:https://api.exemplo.com!/",
            "http://api.exemplo.com/",
            "//api.exemplo.com/",
            "api.exemplo.com/x",
            "",
            "https://user:senha@api.exemplo.com/",
            "https://api.exemplo.com@evil.example/",
            "https://api.exemplo.com:@evil.example/",
            "https://api.exemplo.com#@evil.example/",
            "https://api.exemplo.com\\@evil.example/",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://[::ffff:127.0.0.1]/",
            "https://2130706433/",
            "https://0x7f.0.0.1/",
            "https://017700000001/",
            "https://127.1/",
            "https://localhost/",
            "https://intranet/",
            "https://api.exemplo.com./",
            "https://api..exemplo.com/",
            "https://.exemplo.com/",
            "https://api.exemplo.com/\r\nHost: evil.example",
            "https://api.exemplo.com/ x",
            "https://api.exemplo.com/ç",
            "https://api.exemplo.cõm/",
            "https://api.exemplo.com:0/",
            "https://api.exemplo.com:99999/",
            "https://api.exemplo.com:abc/",
            "https://" + "a".repeat(300) + ".com/",
            "https://api.exemplo.com/" + "x".repeat(2100));
    for (String url : urls) {
      assertThatThrownBy(() -> fetch(strict, url))
          .as(url)
          .isInstanceOf(OutboundHttp.OutboundDenied.class);
    }
    assertThat(HITS.get()).isEqualTo(before);
  }

  @Test
  void nullUrlsAndOddMethodsAreRefused() {
    assertThatThrownBy(() -> strict.session("acme", false).fetch("GET", null, "{}", "null"))
        .isInstanceOf(OutboundHttp.OutboundDenied.class);
    for (String method : new String[] {"CONNECT", "TRACE", "OPTIONS", "HEAD", "", "G ET", null}) {
      assertThatThrownBy(
              () ->
                  strict
                      .session("acme", false)
                      .fetch(method, "https://api.exemplo.com/", "{}", "null"))
          .as(String.valueOf(method))
          .isInstanceOf(OutboundHttp.OutboundDenied.class)
          .hasMessageContaining("Método");
    }
  }

  // ---- lista de permissão -------------------------------------------------------------

  @Test
  void onlyHostsTheTenantAllowedAreReachable() {
    assertThatThrownBy(() -> fetch(strict, "https://outro.exemplo.com/"))
        .hasMessageContaining("não liberado");
    assertThatThrownBy(
            () ->
                strict
                    .session("beta", false)
                    .fetch("GET", "https://api.exemplo.com/", "{}", "null"))
        .as("outro tenant")
        .hasMessageContaining("não liberado");
    assertThatThrownBy(() -> fetch(strict, "https://api.exemplo.com:8443/"))
        .as("porta diferente da liberada")
        .hasMessageContaining("não liberado");
    assertThatThrownBy(() -> fetch(strict, "https://sub.api.exemplo.com/"))
        .as("subdomínio não herda")
        .hasMessageContaining("não liberado");
    assertThatThrownBy(() -> fetch(strict, "https://exemplo.com/"))
        .hasMessageContaining("não liberado");
  }

  @Test
  void hostNamesAreNormalizedBeforeTheAllowListCheck() {
    // maiúsculas viram minúsculas: chega à resolução de DNS (e é recusada lá, pois o host
    // público do teste não responde), nunca é recusada como "não liberado"
    assertThatThrownBy(() -> fetch(strict, "HTTPS://API.EXEMPLO.COM/x"))
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("não liberado"));
  }

  // ---- DNS que aponta para dentro -----------------------------------------------------

  @Test
  void anAllowedNameThatResolvesToANonPublicAddressIsBlockedAtConnectTime() {
    int before = HITS.get();
    for (List<String> ips :
        List.of(
            List.of("127.0.0.1"),
            List.of("10.0.0.5"),
            List.of("169.254.169.254"),
            List.of("::1"),
            List.of("::ffff:192.168.0.1"),
            List.of("93.184.216.34", "127.0.0.1"))) {
      DNS.put("evil.exemplo.com", ips);
      assertThatThrownBy(() -> fetch(strict, "https://evil.exemplo.com/"))
          .as(ips.toString())
          .isInstanceOf(OutboundHttp.OutboundDenied.class)
          .hasMessage("Destino não permitido");
    }
    assertThat(HITS.get()).isEqualTo(before);
  }

  // ---- caminho feliz e proteções da resposta ------------------------------------------

  private String url(String path) {
    return "http://api.test:" + port + path;
  }

  @Test
  void aSuccessfulCallCarriesOnlyWhatWasAskedAndReturnsTheResponse() {
    JsonNode out =
        ok(
            open.session("acme", false)
                .fetch(
                    "POST",
                    url("/eco"),
                    "{\"X-Teste\":\"abc\"}",
                    JSON.writeValueAsString("{\"a\":1}")));

    assertThat(out.path("status").asInt()).isEqualTo(200);
    assertThat(out.path("ok").asBoolean()).isTrue();
    assertThat(out.path("headers").path("x-resposta").asString()).isEqualTo("ok");
    assertThat(out.path("headers").has("set-cookie")).as("cookies não voltam ao script").isFalse();
    assertThat(out.path("body").asString())
        .isEqualTo("POST|{\"a\":1}|abc|application/json; charset=UTF-8|Liberty/1|null|null");
  }

  @Test
  void redirectsAreNotFollowed() {
    int before = HITS.get();
    JsonNode out = ok(fetch(open, url("/redireciona")));

    assertThat(out.path("status").asInt()).isEqualTo(302);
    assertThat(out.path("ok").asBoolean()).isFalse();
    assertThat(HITS.get()).as("só a primeira chamada saiu").isEqualTo(before + 1);
  }

  @Test
  void oversizedResponsesAreRefusedWithAndWithoutContentLength() {
    assertThatThrownBy(() -> fetch(open, url("/grande"))).hasMessageContaining("grande demais");
    assertThatThrownBy(() -> fetch(open, url("/grande-declarado")))
        .hasMessageContaining("grande demais");
  }

  @Test
  void slowServersHitTheDeadline() {
    long start = System.nanoTime();

    assertThatThrownBy(() -> fetch(open, url("/lento"))).hasMessageContaining("Tempo esgotado");
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3500);
  }

  @Test
  void connectionFailuresAreGenericAndNeverLeakDetails() {
    ALLOWED.add("acme|desligado.test:9");
    DNS.put("desligado.test", List.of("127.0.0.1"));

    assertThatThrownBy(() -> fetch(open, "http://desligado.test:9/"))
        .isInstanceOf(OutboundHttp.OutboundDenied.class)
        .hasMessage("Falha na chamada HTTP");
  }

  // ---- limites da requisição ----------------------------------------------------------

  @Test
  void headersBodiesAndCallCountsAreBounded() {
    var session = open.session("acme", false);
    for (String headers :
        List.of(
            "{\"Host\":\"evil.example\"}",
            "{\"host\":\"evil.example\"}",
            "{\"Content-Length\":\"5\"}",
            "{\"Transfer-Encoding\":\"chunked\"}",
            "{\"Cookie\":\"a=b\"}",
            "{\"Connection\":\"upgrade\"}",
            "{\"X-Forwarded-For\":\"1.2.3.4\"}",
            "{\"Accept-Encoding\":\"gzip\"}",
            "{\"X-A\":\"v\\r\\nHost: evil\"}",
            "{\"X A\":\"v\"}",
            "{\"X-A\":5}",
            "[]",
            java.util.stream.IntStream.range(0, 20)
                .mapToObj(n -> "\"a" + n + "\":\"v\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}")))) {
      assertThatThrownBy(
              () -> open.session("acme", false).fetch("GET", url("/eco"), headers, "null"))
          .as(headers)
          .isInstanceOf(OutboundHttp.OutboundDenied.class);
    }
    assertThatThrownBy(
            () -> open.session("acme", false).fetch("GET", url("/eco"), "{}", "\"corpo\""))
        .as("corpo em GET")
        .hasMessageContaining("Corpo");
    assertThatThrownBy(
            () ->
                open.session("acme", false)
                    .fetch("POST", url("/eco"), "{}", JSON.writeValueAsString("x".repeat(2000))))
        .hasMessageContaining("grande demais");

    for (int i = 0; i < 3; i++) {
      session.fetch("GET", url("/eco"), "{}", "null");
    }
    assertThatThrownBy(() -> session.fetch("GET", url("/eco"), "{}", "null"))
        .hasMessageContaining("Limite de chamadas");
  }

  // ---- sandbox ------------------------------------------------------------------------

  @Test
  void theSandboxValidatesEverythingButNeverSendsAnything() {
    int before = HITS.get();
    var session = open.session("acme", true);

    JsonNode simulated = ok(session.fetch("POST", url("/eco"), "{}", "\"x\""));

    assertThat(simulated.path("status").asInt()).isEqualTo(200);
    assertThat(simulated.path("simulated").asBoolean()).isTrue();
    assertThat(HITS.get()).isEqualTo(before);
    assertThatThrownBy(
            () ->
                open.session("acme", true).fetch("GET", "https://outro.exemplo.com/", "{}", "null"))
        .as("a lista de permissão vale no sandbox")
        .hasMessageContaining("não liberado");
    assertThatThrownBy(
            () -> open.session("acme", true).fetch("GET", "https://127.0.0.1/", "{}", "null"))
        .as("IP literal é recusado no sandbox")
        .isInstanceOf(OutboundHttp.OutboundDenied.class);
  }
}
