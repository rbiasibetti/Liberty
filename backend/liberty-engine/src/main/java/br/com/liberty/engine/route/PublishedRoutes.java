package br.com.liberty.engine.route;

import br.com.liberty.domain.tenancy.TenantScope;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Rotas servidas por tenant e ambiente, em cache. A atualização é por consulta curta: a cada
 * intervalo (padrão 5 s) a marca das publicações é conferida e a tabela só é relida (e os snapshots
 * reanalisados) se mudou, então publicar em qualquer instância vale nas outras em até esse
 * intervalo, sem canal extra. Se a releitura falhar, a tabela anterior continua servindo. {@link
 * #invalidate} vale na hora na instância que publicou.
 */
public final class PublishedRoutes {

  private final LoadingCache<TenantScope, PublicationSource.Loaded> cache;

  public PublishedRoutes(PublicationSource source, Duration refreshInterval) {
    this(source, refreshInterval, Ticker.systemTicker(), Runnable::run);
  }

  PublishedRoutes(
      PublicationSource source, Duration refreshInterval, Ticker ticker, Executor refreshExecutor) {
    this.cache =
        Caffeine.newBuilder()
            .ticker(ticker)
            .executor(refreshExecutor)
            .refreshAfterWrite(refreshInterval)
            .expireAfterAccess(Duration.ofMinutes(30))
            .maximumSize(20_000)
            .build(
                new CacheLoader<>() {
                  @Override
                  public PublicationSource.Loaded load(TenantScope scope) {
                    return source.load(scope);
                  }

                  @Override
                  public PublicationSource.Loaded reload(
                      TenantScope scope, PublicationSource.Loaded old) {
                    return Objects.equals(source.token(scope), old.token())
                        ? old
                        : source.load(scope);
                  }
                });
  }

  public RouteTable.Resolution resolve(TenantScope scope, String method, List<String> path) {
    return cache.get(scope).table().resolve(method, path);
  }

  /** A integração publicada em {@code scope} com esta chave. */
  public java.util.Optional<PublishedIntegration> find(TenantScope scope, String key) {
    return cache.get(scope).table().byKey(key);
  }

  /** Descarta a tabela do tenant: a próxima requisição relê as publicações. */
  public void invalidate(TenantScope scope) {
    cache.invalidate(scope);
  }
}
