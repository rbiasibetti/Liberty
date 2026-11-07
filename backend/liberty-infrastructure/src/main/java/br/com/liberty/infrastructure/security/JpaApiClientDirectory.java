package br.com.liberty.infrastructure.security;

import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Diretório sobre o banco central, com cache de leitura de vida curta para a validação por
 * requisição.
 */
public final class JpaApiClientDirectory implements ApiClientDirectory {

  private final ApiClientRepository clients;
  private final TenantRepository tenants;
  private final TransactionTemplate readOnly;
  private final Clock clock;
  private final Cache<String, Optional<ApiClient>> cache;

  public JpaApiClientDirectory(
      ApiClientRepository clients,
      TenantRepository tenants,
      TransactionTemplate readOnly,
      Clock clock,
      Duration cacheTtl) {
    this(clients, tenants, readOnly, clock, cacheTtl, Ticker.systemTicker());
  }

  JpaApiClientDirectory(
      ApiClientRepository clients,
      TenantRepository tenants,
      TransactionTemplate readOnly,
      Clock clock,
      Duration cacheTtl,
      Ticker ticker) {
    this.clients = clients;
    this.tenants = tenants;
    this.readOnly = readOnly;
    this.clock = clock;
    this.cache =
        Caffeine.newBuilder().expireAfterWrite(cacheTtl).ticker(ticker).maximumSize(10_000).build();
  }

  @Override
  public Optional<ApiClient> findFresh(String clientId) {
    Optional<ApiClient> found = load(clientId);
    cache.put(clientId, found);
    return found;
  }

  @Override
  public Optional<ApiClient> findCached(String clientId) {
    return cache.get(clientId, this::load);
  }

  private Optional<ApiClient> load(String clientId) {
    return readOnly.execute(status -> clients.findByClientId(clientId).flatMap(this::toClient));
  }

  private Optional<ApiClient> toClient(ApiClientEntity client) {
    return tenants
        .findById(client.getTenantId())
        .map(
            tenant -> {
              Instant now = clock.instant();
              boolean usable =
                  "ACTIVE".equals(client.getStatus())
                      && client.getRevokedAt() == null
                      && (client.getExpiresAt() == null || client.getExpiresAt().isAfter(now))
                      && "ACTIVE".equals(tenant.getStatus());
              var scopes = new HashSet<>(Arrays.asList(client.getScopes().trim().split("\\s+")));
              scopes.remove("");
              return new ApiClient(
                  client.getClientId(),
                  client.getSecretHash(),
                  tenant.getSlug(),
                  client.getEnvironment(),
                  java.util.Set.copyOf(scopes),
                  usable);
            });
  }
}
