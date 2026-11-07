package br.com.liberty.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.infrastructure.tenancy.LibertyVaultProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityInfrastructureConfigTest {

  private static LibertySecurityProperties properties(LibertySecurityProperties.Keys.Store store) {
    return new LibertySecurityProperties(
        URI.create("http://liberty.test"),
        "liberty-api",
        Duration.ofMinutes(15),
        Duration.ofSeconds(15),
        new LibertySecurityProperties.Keys(
            store, "liberty/jwt/keys", Duration.ofDays(90), Duration.ofHours(24)),
        new LibertySecurityProperties.Oidc(
            URI.create("http://oidc.test/realm"), "liberty-frontend", "admin"),
        List.of());
  }

  private static final LibertyVaultProperties VAULT =
      new LibertyVaultProperties(
          URI.create("http://127.0.0.1:1"),
          "secret",
          LibertyVaultProperties.Authentication.TOKEN,
          "t",
          null,
          null);

  @Test
  void memoryStoreOnlyWhenExplicitlyConfigured() {
    var config = new SecurityInfrastructureConfig();

    assertThat(
            config.signingKeyStore(
                null, VAULT, properties(LibertySecurityProperties.Keys.Store.MEMORY)))
        .isInstanceOf(InMemorySigningKeyStore.class);
  }

  @Test
  void defaultsToTheVaultStore() {
    var config = new SecurityInfrastructureConfig();
    var vault =
        br.com.liberty.infrastructure.tenancy.TenantPersistenceConfigAccess.vaultTemplate(VAULT);

    assertThat(
            config.signingKeyStore(
                vault, VAULT, properties(LibertySecurityProperties.Keys.Store.VAULT)))
        .isInstanceOf(VaultSigningKeyStore.class);
  }

  @Test
  void buildsTheRingWithTheConfiguredRotationAndTheSystemClock() {
    var config = new SecurityInfrastructureConfig();
    var props = properties(LibertySecurityProperties.Keys.Store.MEMORY);

    assertThat(config.clock()).isEqualTo(Clock.systemUTC());
    assertThat(config.signingKeyRing(new InMemorySigningKeyStore(), Clock.systemUTC(), props))
        .isNotNull();
  }
}
