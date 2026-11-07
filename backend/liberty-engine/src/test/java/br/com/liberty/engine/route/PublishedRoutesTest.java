package br.com.liberty.engine.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PublishedRoutesTest {

  private static final TenantScope SCOPE =
      new TenantScope(new TenantId("acme"), EnvironmentKind.HML);
  private static final TenantScope OTHER =
      new TenantScope(new TenantId("beta"), EnvironmentKind.HML);

  private static final class FakeSource implements PublicationSource {
    final AtomicInteger loads = new AtomicInteger();
    final AtomicInteger tokens = new AtomicInteger();
    volatile String token = "v1";
    volatile List<String> routes = List.of("/a");
    volatile boolean failing;

    @Override
    public Loaded load(TenantScope scope) {
      loads.incrementAndGet();
      if (failing) {
        throw new IllegalStateException("core fora do ar");
      }
      return new Loaded(
          token,
          RouteTable.of(
              routes.stream()
                  .map(
                      r ->
                          new PublishedIntegration(
                              r.hashCode(), r, "GET", 1, 1, null, RoutePattern.parse(r)))
                  .toList()));
    }

    @Override
    public String token(TenantScope scope) {
      tokens.incrementAndGet();
      if (failing) {
        throw new IllegalStateException("core fora do ar");
      }
      return token;
    }
  }

  private final FakeSource source = new FakeSource();
  private final AtomicLong clock = new AtomicLong();
  private final Ticker ticker = clock::get;
  private final PublishedRoutes routes =
      new PublishedRoutes(source, Duration.ofSeconds(5), ticker, Runnable::run);

  private void advance(Duration d) {
    clock.addAndGet(d.toNanos());
  }

  private boolean serves(TenantScope scope, String segment) {
    return routes.resolve(scope, "GET", List.of(segment)).match().isPresent();
  }

  @Test
  void loadsOnceAndServesFromCacheBeforeTheRefreshInterval() {
    assertThat(serves(SCOPE, "a")).isTrue();
    assertThat(serves(SCOPE, "a")).isTrue();
    advance(Duration.ofSeconds(4));
    assertThat(serves(SCOPE, "a")).isTrue();

    assertThat(source.loads.get()).isEqualTo(1);
    assertThat(source.tokens.get()).isZero();
  }

  @Test
  void afterTheIntervalOnlyTheCheapTokenIsCheckedWhileNothingChanged() {
    serves(SCOPE, "a");

    advance(Duration.ofSeconds(6));
    serves(SCOPE, "a");
    advance(Duration.ofSeconds(6));
    serves(SCOPE, "a");

    assertThat(source.loads.get()).as("sem releitura completa").isEqualTo(1);
    assertThat(source.tokens.get()).isEqualTo(2);
  }

  @Test
  void aNewTokenReloadsTheTableAndPublishingBecomesVisible() {
    assertThat(serves(SCOPE, "novo")).isFalse();
    source.token = "v2";
    source.routes = List.of("/a", "/novo");

    advance(Duration.ofSeconds(6));
    serves(SCOPE, "a"); // dispara a atualização
    boolean visible = serves(SCOPE, "novo");

    assertThat(visible).isTrue();
    assertThat(source.loads.get()).isEqualTo(2);
  }

  @Test
  void unpublishingStopsServingAfterTheInterval() {
    assertThat(serves(SCOPE, "a")).isTrue();
    source.token = "v2";
    source.routes = List.of();

    advance(Duration.ofSeconds(6));
    serves(SCOPE, "a");

    assertThat(serves(SCOPE, "a")).isFalse();
  }

  @Test
  void aFailingRefreshKeepsServingThePreviousTable() {
    assertThat(serves(SCOPE, "a")).isTrue();
    source.failing = true;

    advance(Duration.ofSeconds(6));
    boolean first = serves(SCOPE, "a");
    advance(Duration.ofSeconds(6));
    boolean second = serves(SCOPE, "a");

    assertThat(first).isTrue();
    assertThat(second).isTrue();
  }

  @Test
  void aFailingFirstLoadPropagatesAndIsNotCached() {
    source.failing = true;

    assertThatThrownBy(() -> serves(SCOPE, "a")).isInstanceOf(RuntimeException.class);
    source.failing = false;

    assertThat(serves(SCOPE, "a")).isTrue();
  }

  @Test
  void invalidateReloadsImmediatelyOnTheNextRequest() {
    serves(SCOPE, "a");
    source.token = "v2";
    source.routes = List.of("/novo");

    routes.invalidate(SCOPE);

    assertThat(serves(SCOPE, "novo")).isTrue();
    assertThat(serves(SCOPE, "a")).isFalse();
  }

  @Test
  void tenantsAreCachedSeparately() {
    serves(SCOPE, "a");
    serves(OTHER, "a");
    routes.invalidate(SCOPE);
    serves(OTHER, "a");

    assertThat(source.loads.get()).isEqualTo(2);
  }
}
