package br.com.liberty.scripts.worker;

import java.util.function.BiFunction;
import org.graalvm.polyglot.HostAccess;

/**
 * Única superfície Java visível ao script (só o que leva {@code @HostAccess.Export}), e só através
 * do prelúdio que expõe {@code db} e {@code fail}. Toda chamada vai ao pai pelo canal do
 * trabalhador; o trabalhador não tem conexão, credencial nem segredo.
 */
public final class HostBridge {

  /** Recusa de negócio pedida pelo script. */
  public static final class BusinessFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    BusinessFailure(int status, String message) {
      super(message);
      this.status = status;
    }

    int status() {
      return status;
    }
  }

  /** Envia {@code (op, argumentos)} ao pai e devolve o JSON da resposta, ou lança com o erro. */
  interface Channel extends BiFunction<String, String[], String> {}

  private final Channel channel;

  HostBridge(Channel channel) {
    this.channel = channel;
  }

  @HostAccess.Export
  public String named(String name, String paramsJson) {
    return channel.apply("named", new String[] {name, paramsJson});
  }

  @HostAccess.Export
  public String lookup(String table, String keyColumn, String keyJson, String valueColumn) {
    return channel.apply("lookup", new String[] {table, keyColumn, keyJson, valueColumn});
  }

  @HostAccess.Export
  public String fetch(String method, String url, String headersJson, String bodyJson) {
    return channel.apply("fetch", new String[] {method, url, headersJson, bodyJson});
  }

  @HostAccess.Export
  public void fail(int status, String message) {
    int http = status >= 400 && status <= 499 ? status : 422;
    String text = message.length() > 300 ? message.substring(0, 300) : message;
    throw new BusinessFailure(http, text);
  }
}
