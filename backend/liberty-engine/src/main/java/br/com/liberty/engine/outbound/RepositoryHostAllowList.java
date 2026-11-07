package br.com.liberty.engine.outbound;

import br.com.liberty.infrastructure.persistence.TenantHttpHostRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;

/** Lista de permissão no banco central, com cache curto: retirar um host vale em até 5 s. */
public final class RepositoryHostAllowList implements HostAllowList {

  private record Key(String slug, String host) {}

  private final LoadingCache<Key, Boolean> cache;

  public RepositoryHostAllowList(TenantHttpHostRepository repository) {
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build(key -> lookup(repository, key));
  }

  static Boolean lookup(TenantHttpHostRepository repository, Key key) {
    return repository.allowed(key.slug(), key.host());
  }

  @Override
  public boolean allows(String tenantSlug, String host) {
    return Boolean.TRUE.equals(cache.get(new Key(tenantSlug, host)));
  }
}
