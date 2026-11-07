package br.com.liberty.engine.outbound;

/** Hosts que o tenant liberou para o {@code http.fetch}. */
public interface HostAllowList {

  /** {@code host} já normalizado: minúsculas, com ":porta" se não for a padrão. */
  boolean allows(String tenantSlug, String host);
}
