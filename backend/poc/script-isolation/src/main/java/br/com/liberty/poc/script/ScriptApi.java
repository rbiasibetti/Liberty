package br.com.liberty.poc.script;

import org.graalvm.polyglot.HostAccess;

/** Única superfície Java visível ao script: só métodos anotados com {@code @HostAccess.Export}. */
public final class ScriptApi {

  /** Recusa de negócio pedida pelo script (vira 4xx no motor real). */
  public static final class BusinessFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BusinessFailure(String message) {
      super(message);
    }
  }

  @HostAccess.Export
  public String lookup(String table, String key) {
    return table + ":" + key;
  }

  @HostAccess.Export
  public void fail(int status, String message) {
    throw new BusinessFailure(status + " " + message);
  }

  /** Não exportado: o script não pode chamar. */
  public String secret() {
    return "nunca-visivel";
  }
}
