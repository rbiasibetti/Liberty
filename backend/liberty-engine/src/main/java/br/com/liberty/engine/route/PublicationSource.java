package br.com.liberty.engine.route;

import br.com.liberty.domain.tenancy.TenantScope;

/**
 * De onde vêm as integrações servidas (o core): leitura completa e uma marca barata para detectar
 * mudança.
 */
public interface PublicationSource {

  /** Integrações e a marca do conjunto, lidas juntas. */
  record Loaded(String token, RouteTable table) {}

  Loaded load(TenantScope scope);

  /**
   * Só a marca: igual à de {@link #load} se nada foi publicado, despublicado ou revertido desde
   * então.
   */
  String token(TenantScope scope);
}
