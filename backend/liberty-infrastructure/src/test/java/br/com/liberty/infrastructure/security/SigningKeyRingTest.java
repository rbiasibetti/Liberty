package br.com.liberty.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SigningKeyRingTest {

  private static final Duration PERIOD = Duration.ofDays(90);
  private static final Duration GRACE = Duration.ofHours(24);

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-06T12:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  private static SigningKeyRing ring(InMemorySigningKeyStore store, MutableClock clock) {
    return new SigningKeyRing(store, clock, PERIOD, GRACE, 2048);
  }

  @Test
  void createsTheFirstKeyOnlyOnce() {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    var ring = ring(store, clock);

    ring.rotateIfDue();
    String kid = ring.current().kid();
    ring.rotateIfDue();

    assertThat(ring.current().kid()).isEqualTo(kid);
    assertThat(store.load().version()).isEqualTo(1);
    assertThat(ring.current().state()).isEqualTo(SigningKey.State.ACTIVE);
  }

  @Test
  void publishesOnlyPublicParts() {
    var ring = ring(new InMemorySigningKeyStore(), new MutableClock());
    ring.rotateIfDue();

    RSAKey published = (RSAKey) ring.publicJwkSet().getKeys().getFirst();
    RSAKey signing = (RSAKey) ring.signingJwkSet().getKeys().getFirst();

    assertThat(published.isPrivate()).isFalse();
    assertThat(published.getKeyID()).isEqualTo(ring.current().kid());
    assertThat(signing.isPrivate()).isTrue();
    assertThat(ring.signingJwkSet().getKeys()).hasSize(1);
  }

  @Test
  void rotatesAfterThePeriodAndKeepsTheOldKeyForVerification() {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    var ring = ring(store, clock);
    ring.rotateIfDue();
    String first = ring.current().kid();

    clock.advance(PERIOD.plusDays(1));
    ring.rotateIfDue();

    assertThat(ring.current().kid()).isNotEqualTo(first);
    assertThat(ring.publicJwkSet().getKeys()).hasSize(2);
    assertThat(ring.signingJwkSet().getKeys()).hasSize(1);
    assertThat(store.load().keys())
        .filteredOn(k -> k.kid().equals(first))
        .singleElement()
        .satisfies(k -> assertThat(k.state()).isEqualTo(SigningKey.State.RETIRING));
  }

  @Test
  void dropsRetiredKeysAfterTheGracePeriod() {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    var ring = ring(store, clock);
    ring.rotateIfDue();
    clock.advance(PERIOD.plusDays(1));
    ring.rotateIfDue();
    assertThat(ring.publicJwkSet().getKeys()).hasSize(2);

    clock.advance(GRACE.plusMinutes(1));
    ring.rotateIfDue();

    assertThat(ring.publicJwkSet().getKeys()).hasSize(1);
    assertThat(store.load().keys())
        .singleElement()
        .satisfies(k -> assertThat(k.state()).isEqualTo(SigningKey.State.ACTIVE));
  }

  @Test
  void nothingChangesWhileTheKeyIsFresh() {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    var ring = ring(store, clock);
    ring.rotateIfDue();

    clock.advance(PERIOD.minusDays(1));
    ring.rotateIfDue();

    assertThat(store.load().version()).isEqualTo(1);
  }

  @Test
  void instancesRacingToRotateProduceExactlyOneNewKey() throws Exception {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    ring(store, clock).rotateIfDue();
    clock.advance(PERIOD.plusDays(1));

    int instances = 6;
    List<SigningKeyRing> rings = new ArrayList<>();
    for (int i = 0; i < instances; i++) {
      rings.add(ring(store, clock));
    }
    CountDownLatch go = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(instances)) {
      List<Future<?>> futures = new ArrayList<>();
      for (SigningKeyRing ring : rings) {
        futures.add(
            executor.submit(
                () -> {
                  go.await();
                  ring.rotateIfDue();
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(store.load().keys()).hasSize(2);
    assertThat(store.load().keys())
        .filteredOn(k -> k.state() == SigningKey.State.ACTIVE)
        .hasSize(1);
    String winner =
        store.load().keys().stream()
            .filter(k -> k.state() == SigningKey.State.ACTIVE)
            .findFirst()
            .orElseThrow()
            .kid();
    rings.forEach(r -> assertThat(r.current().kid()).isEqualTo(winner));
  }

  @Test
  void refreshPicksUpKeysRotatedByAnotherInstance() {
    var store = new InMemorySigningKeyStore();
    var clock = new MutableClock();
    var a = ring(store, clock);
    var b = ring(store, clock);
    a.rotateIfDue();
    b.refresh();
    assertThat(b.current().kid()).isEqualTo(a.current().kid());

    clock.advance(PERIOD.plusDays(1));
    a.rotateIfDue();
    b.refresh();

    assertThat(b.current().kid()).isEqualTo(a.current().kid());
    assertThat(b.publicJwkSet().getKeys()).hasSize(2);
  }

  @Test
  void withoutKeysThereIsNothingToSignWith() {
    assertThatIllegalStateException()
        .isThrownBy(() -> ring(new InMemorySigningKeyStore(), new MutableClock()).current());
  }

  @Test
  void keyDescriptionNeverPrintsMaterial() {
    var ring = ring(new InMemorySigningKeyStore(), new MutableClock());
    ring.rotateIfDue();

    assertThat(ring.current().toString()).startsWith("SigningKey[kid=").doesNotContain("Modulus");
  }
}
