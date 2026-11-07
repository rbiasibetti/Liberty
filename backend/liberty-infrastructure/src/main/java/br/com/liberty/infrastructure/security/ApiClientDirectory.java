package br.com.liberty.infrastructure.security;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import java.util.Optional;
import java.util.Set;

/**
 * Catálogo dos clientes de API (sistemas consumidores). A revogação vale no máximo após o TTL do
 * cache.
 */
public interface ApiClientDirectory {

  /**
   * Cliente como o core o descreve; {@code usable} é falso se revogado, expirado ou de tenant
   * inativo.
   */
  record ApiClient(
      String clientId,
      String secretHash,
      String tenantSlug,
      EnvironmentKind environment,
      Set<String> scopes,
      boolean usable) {

    @Override
    public String toString() {
      return "ApiClient[clientId="
          + clientId
          + ", tenant="
          + tenantSlug
          + ", env="
          + environment
          + "]";
    }
  }

  /** Leitura direta do core: usada na emissão de tokens, que nunca pode usar dado velho. */
  Optional<ApiClient> findFresh(String clientId);

  /** Leitura com cache curto: usada na validação de cada requisição. */
  Optional<ApiClient> findCached(String clientId);
}
