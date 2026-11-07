package br.com.liberty.engine.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code http.fetch} dos scripts (ADR 0034). Defesa em camadas contra SSRF: só HTTPS; só hosts que
 * o tenant liberou (nome DNS, nunca IP); todo endereço resolvido tem de ser público, checado no
 * mesmo momento da conexão (sem janela para reassociação de DNS); sem redirecionamentos, proxy,
 * cookies nem descompressão; prazo total, tamanhos e número de chamadas limitados. No sandbox nada
 * sai: a chamada passa pela mesma validação e recebe uma resposta simulada.
 */
public final class OutboundHttp implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(OutboundHttp.class);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
  private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern HOST_CHARS = Pattern.compile("[a-z0-9.-]{1,253}");
  private static final Set<String> FORBIDDEN_HEADERS =
      Set.of(
          "host",
          "content-length",
          "transfer-encoding",
          "connection",
          "upgrade",
          "te",
          "trailer",
          "expect",
          "keep-alive",
          "proxy-authorization",
          "proxy-authenticate",
          "proxy-connection",
          "cookie",
          "set-cookie",
          "accept-encoding",
          "forwarded",
          "x-forwarded-for",
          "x-forwarded-host",
          "x-real-ip");
  private static final int MAX_URL = 2048;
  private static final int MAX_HEADERS = 16;
  private static final int MAX_HEADER_VALUE = 4096;

  /** Recusa com texto seguro para o script ver; detalhes técnicos nunca vão junto. */
  public static final class OutboundDenied extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    OutboundDenied(String message) {
      super(message);
    }
  }

  private static final class BlockedAddress extends UnknownHostException {
    private static final long serialVersionUID = 1L;

    BlockedAddress() {
      super("destino bloqueado");
    }
  }

  private final OutboundProperties properties;
  private final HostAllowList allowList;
  private final boolean allowPlainHttp;
  private final CloseableHttpClient client;
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "outbound-deadline");
            t.setDaemon(true);
            return t;
          });

  /** Produção: DNS do sistema, só endereços públicos, só HTTPS. */
  public OutboundHttp(OutboundProperties properties, HostAllowList allowList) {
    this(properties, allowList, new SystemDefaultDnsResolver(), AddressPolicy.PUBLIC_ONLY, false);
  }

  /** Para testes: resolvedor, política de endereços e HTTP puro substituíveis. */
  OutboundHttp(
      OutboundProperties properties,
      HostAllowList allowList,
      DnsResolver dns,
      AddressPolicy policy,
      boolean allowPlainHttp) {
    this.properties = properties;
    this.allowList = allowList;
    this.allowPlainHttp = allowPlainHttp;
    DnsResolver guarded =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] all = dns.resolve(host);
            for (InetAddress address : all) {
              if (!policy.allows(address)) {
                throw new BlockedAddress();
              }
            }
            return all;
          }

          @Override
          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return dns.resolveCanonicalHostname(host);
          }
        };
    PoolingHttpClientConnectionManager manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(guarded)
            .setMaxConnTotal(properties.maxConnections())
            .setMaxConnPerRoute(Math.max(2, properties.maxConnections() / 4))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                    .setSocketTimeout(Timeout.of(properties.timeout()))
                    .build())
            .build();
    this.client =
        HttpClients.custom()
            .setConnectionManager(manager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(properties.timeout()))
                    .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
                    .setRedirectsEnabled(false)
                    .build())
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAutomaticRetries()
            .disableContentCompression()
            .disableAuthCaching()
            .setUserAgent("Liberty/1")
            .build();
  }

  /** Prazo total de uma chamada. */
  public java.time.Duration timeout() {
    return properties.timeout();
  }

  /** Uma execução de script: cada uma tem seu contador de chamadas. */
  public Session session(String tenantSlug, boolean simulate) {
    return new Session(tenantSlug, simulate);
  }

  /** Chamadas de uma execução de script. */
  public final class Session {
    private final String tenant;
    private final boolean simulate;
    private int calls;

    private Session(String tenant, boolean simulate) {
      this.tenant = tenant;
      this.simulate = simulate;
    }

    /**
     * Devolve {@code {status, ok, headers, body}} em JSON; recusas viram {@link OutboundDenied}.
     */
    public String fetch(String method, String url, String headersJson, String bodyJson) {
      if (++calls > properties.maxCallsPerExecution()) {
        throw new OutboundDenied("Limite de chamadas HTTP por execução excedido");
      }
      return OutboundHttp.this.fetch(tenant, simulate, method, url, headersJson, bodyJson);
    }
  }

  private String fetch(
      String tenant,
      boolean simulate,
      String methodText,
      String url,
      String headersJson,
      String bodyJson) {
    String method = methodText == null ? "" : methodText.toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      throw new OutboundDenied("Método HTTP não permitido");
    }
    Target target = parse(url);
    if (!allowList.allows(tenant, target.key())) {
      throw new OutboundDenied("Host não liberado para este tenant");
    }
    Map<String, String> headers = headers(headersJson);
    String body = body(method, bodyJson);
    if (simulate) {
      ObjectNode simulated = MAPPER.createObjectNode();
      simulated.put("status", 200).put("ok", true).put("simulated", true).put("body", "");
      simulated.putObject("headers");
      return MAPPER.writeValueAsString(simulated);
    }
    return execute(tenant, target, method, headers, body);
  }

  private record Target(URI uri, String key) {}

  private Target parse(String url) {
    if (url == null || url.isEmpty() || url.length() > MAX_URL) {
      throw new OutboundDenied("URL inválida");
    }
    for (int i = 0; i < url.length(); i++) {
      char c = url.charAt(i);
      if (c <= ' ' || c == '\\' || c == 127 || c > 126) {
        throw new OutboundDenied("URL inválida");
      }
    }
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new OutboundDenied("URL inválida");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean https = scheme.equals("https");
    if (!https && !(allowPlainHttp && scheme.equals("http"))) {
      throw new OutboundDenied("Só HTTPS é permitido");
    }
    if (uri.getRawUserInfo() != null || uri.getRawAuthority() == null) {
      throw new OutboundDenied("URL inválida");
    }
    String host = uri.getHost();
    if (host == null) {
      throw new OutboundDenied("URL inválida");
    }
    host = host.toLowerCase(Locale.ROOT);
    if (!HOST_CHARS.matcher(host).matches()
        || host.startsWith(".")
        || host.endsWith(".")
        || host.contains("..")
        || !host.contains(".")
        || !hasLetter(host.substring(host.lastIndexOf('.') + 1))) {
      // recusa IP em qualquer notação (decimal, hexa, octal, IPv6), rótulos vazios e ponto final
      throw new OutboundDenied("Só nomes DNS são permitidos como destino");
    }
    int defaultPort = https ? 443 : 80;
    int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
    if (port < 1 || port > 65535) {
      throw new OutboundDenied("URL inválida");
    }
    String key = port == defaultPort ? host : host + ":" + port;
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    URI clean = URI.create(scheme + "://" + host + ":" + port + path + query);
    return new Target(clean, key);
  }

  private static boolean hasLetter(String label) {
    for (int i = 0; i < label.length(); i++) {
      if (Character.isLetter(label.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, String> headers(String json) {
    Map<String, String> out = new LinkedHashMap<>();
    JsonNode node = json == null || json.isEmpty() ? null : MAPPER.readTree(json);
    if (node == null || node.isNull()) {
      return out;
    }
    if (!node.isObject() || node.size() > MAX_HEADERS) {
      throw new OutboundDenied("Cabeçalhos inválidos");
    }
    for (var entry : node.properties()) {
      String name = entry.getKey();
      String value = entry.getValue().isString() ? entry.getValue().asString() : null;
      if (value == null
          || !HEADER_NAME.matcher(name).matches()
          || FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))
          || value.length() > MAX_HEADER_VALUE
          || hasControl(value)) {
        throw new OutboundDenied("Cabeçalho não permitido");
      }
      out.put(name, value);
    }
    return out;
  }

  private static boolean hasControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < ' ' && c != '\t' || c == 127) {
        return true;
      }
    }
    return false;
  }

  private String body(String method, String bodyJson) {
    JsonNode node = bodyJson == null || bodyJson.isEmpty() ? null : MAPPER.readTree(bodyJson);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!BODY_METHODS.contains(method) || !node.isString()) {
      throw new OutboundDenied("Corpo não permitido para este método");
    }
    String text = node.asString();
    if (text.getBytes(StandardCharsets.UTF_8).length > properties.maxRequestBytes()) {
      throw new OutboundDenied("Corpo da requisição grande demais");
    }
    return text;
  }

  private String execute(
      String tenant, Target target, String method, Map<String, String> headers, String body) {
    HttpUriRequestBase request = new HttpUriRequestBase(method, target.uri());
    headers.forEach(request::addHeader);
    if (body != null) {
      boolean hasType = headers.keySet().stream().anyMatch(h -> h.equalsIgnoreCase("content-type"));
      request.setEntity(
          new StringEntity(
              body,
              hasType ? null : ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
    }
    AtomicBoolean expired = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        deadlines.schedule(
            () -> {
              expired.set(true);
              request.cancel();
            },
            properties.timeout().toMillis(),
            TimeUnit.MILLISECONDS);
    long start = System.nanoTime();
    try {
      String result =
          client.execute(
              request,
              response -> {
                ObjectNode out = MAPPER.createObjectNode();
                int status = response.getCode();
                out.put("status", status).put("ok", status >= 200 && status < 300);
                ObjectNode responseHeaders = out.putObject("headers");
                for (Header header : response.getHeaders()) {
                  String name = header.getName().toLowerCase(Locale.ROOT);
                  if (!name.equals("set-cookie")) {
                    JsonNode previous = responseHeaders.get(name);
                    responseHeaders.put(
                        name,
                        previous == null
                            ? header.getValue()
                            : previous.asString() + ", " + header.getValue());
                  }
                }
                out.put("body", readBody(response.getEntity()));
                return MAPPER.writeValueAsString(out);
              });
      LOG.info(
          "http.fetch tenant={} host={} método={} em {} ms",
          tenant,
          target.key(),
          method,
          (System.nanoTime() - start) / 1_000_000);
      return result;
    } catch (IOException e) {
      throw failure(tenant, target, e, expired.get());
    } finally {
      deadline.cancel(false);
    }
  }

  private String readBody(org.apache.hc.core5.http.HttpEntity entity) throws IOException {
    if (entity == null) {
      return "";
    }
    int max = properties.maxResponseBytes();
    if (entity.getContentLength() > max) {
      throw new OutboundDenied("Resposta grande demais");
    }
    try (InputStream in = entity.getContent()) {
      byte[] bytes = in.readNBytes(max + 1);
      if (bytes.length > max) {
        throw new OutboundDenied("Resposta grande demais");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static RuntimeException failure(
      String tenant, Target target, IOException e, boolean expired) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof BlockedAddress) {
        LOG.warn(
            "http.fetch bloqueado: tenant={} host={} resolve para endereço não público",
            tenant,
            target.key());
        return new OutboundDenied("Destino não permitido");
      }
    }
    LOG.info(
        "http.fetch falhou: tenant={} host={} ({})",
        tenant,
        target.key(),
        e.getClass().getSimpleName());
    boolean timedOut = expired;
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      String text =
          (cause.getClass().getSimpleName() + " " + cause.getMessage()).toLowerCase(Locale.ROOT);
      timedOut |=
          cause instanceof java.io.InterruptedIOException
              || text.contains("timeout")
              || text.contains("timed out")
              || text.contains("abort")
              || text.contains("cancel");
    }
    return new OutboundDenied(
        timedOut ? "Tempo esgotado na chamada HTTP" : "Falha na chamada HTTP");
  }

  @Override
  public void close() throws IOException {
    deadlines.shutdownNow();
    client.close();
  }
}
