package br.com.liberty.engine.traffic;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Limite de taxa com Bucket4j. Com Redis o balde é compartilhado por todas as instâncias; se o
 * Redis falhar a chamada cai para um balde local (o tráfego continua limitado por instância e a
 * falha é contada em {@code liberty.ratelimit.fallback}), porque derrubar a API junto com o Redis
 * seria pior que limitar com folga. O balde é identificado por tenant, taxa e rajada, então mudar
 * de plano troca o balde.
 */
public final class Bucket4jRateLimiter implements RateLimiter, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRateLimiter.class);

  private static final long RETRY_CONNECT_NANOS = Duration.ofSeconds(10).toNanos();

  private final Map<String, Bucket> local = new ConcurrentHashMap<>();
  private final String redisUri;
  private final String prefix;
  private final Counter fallbacks;
  private RedisClient client;
  private StatefulRedisConnection<String, byte[]> connection;
  private volatile ProxyManager<String> proxies;
  private long lastAttempt = System.nanoTime() - RETRY_CONNECT_NANOS - 1;

  /** Só local (sem Redis). */
  public Bucket4jRateLimiter(MeterRegistry meters) {
    this(null, "", meters);
  }

  /**
   * Com Redis em {@code redisUri} (por exemplo {@code redis://:senha@host:6379}), ou local se
   * vazio. A conexão é feita sob demanda e retentada a cada 10 s: a API sobe e funciona com o Redis
   * fora do ar.
   */
  public Bucket4jRateLimiter(String redisUri, String prefix, MeterRegistry meters) {
    this.redisUri = redisUri;
    this.prefix = prefix;
    this.fallbacks =
        Counter.builder("liberty.ratelimit.fallback")
            .description("Chamadas em que o Redis não estava disponível e o balde local foi usado")
            .register(meters);
  }

  private ProxyManager<String> redis() {
    if (redisUri == null || redisUri.isBlank()) {
      return null;
    }
    ProxyManager<String> current = proxies;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (proxies != null) {
        return proxies;
      }
      if (System.nanoTime() - lastAttempt < RETRY_CONNECT_NANOS) {
        return null;
      }
      lastAttempt = System.nanoTime();
      try {
        io.lettuce.core.RedisURI uri = io.lettuce.core.RedisURI.create(redisUri);
        uri.setTimeout(Duration.ofMillis(500));
        client = RedisClient.create(uri);
        connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        proxies =
            LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(
                    ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofMinutes(2)))
                .build();
        return proxies;
      } catch (RuntimeException e) {
        LOG.warn("Não foi possível conectar ao Redis do limite de taxa: {}", e.toString());
        closeQuietly();
        return null;
      }
    }
  }

  @Override
  public Decision tryAcquire(String tenant, PlanLimits limits) {
    String key = tenant + ":" + limits.ratePerSecond() + ":" + limits.burst();
    BucketConfiguration configuration = configuration(limits);
    if (redisUri != null && !redisUri.isBlank()) {
      ProxyManager<String> manager = redis();
      if (manager != null) {
        try {
          var bucket = manager.builder().build(prefix + key, () -> configuration);
          return decision(bucket.tryConsumeAndReturnRemaining(1), limits);
        } catch (RuntimeException e) {
          LOG.warn("Redis do limite de taxa falhou; usando balde local: {}", e.toString());
          synchronized (this) {
            proxies = null;
            closeQuietly();
          }
        }
      }
      fallbacks.increment();
    }
    Bucket bucket =
        local.computeIfAbsent(key, k -> Bucket.builder().addLimit(limit(limits)).build());
    return decision(bucket.tryConsumeAndReturnRemaining(1), limits);
  }

  private static BucketConfiguration configuration(PlanLimits limits) {
    return BucketConfiguration.builder().addLimit(limit(limits)).build();
  }

  private static io.github.bucket4j.Bandwidth limit(PlanLimits limits) {
    return io.github.bucket4j.Bandwidth.builder()
        .capacity(limits.burst())
        .refillGreedy(limits.ratePerSecond(), Duration.ofSeconds(1))
        .build();
  }

  private static Decision decision(ConsumptionProbe probe, PlanLimits limits) {
    long retry =
        probe.isConsumed()
            ? 0
            : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    return new Decision(
        probe.isConsumed(), limits.ratePerSecond(), probe.getRemainingTokens(), retry);
  }

  private void closeQuietly() {
    try {
      if (connection != null) {
        connection.close();
      }
      if (client != null) {
        client.shutdown(Duration.ZERO, Duration.ofMillis(200));
      }
    } catch (RuntimeException e) {
      LOG.debug("Falha ao fechar a conexão do limite de taxa", e);
    } finally {
      connection = null;
      client = null;
    }
  }

  @Override
  public synchronized void close() {
    proxies = null;
    closeQuietly();
  }
}
