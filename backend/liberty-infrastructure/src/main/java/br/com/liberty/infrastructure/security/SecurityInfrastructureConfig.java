package br.com.liberty.infrastructure.security;

import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.tenancy.LibertyVaultProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.vault.core.VaultTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LibertySecurityProperties.class)
public class SecurityInfrastructureConfig {

  private static final Logger LOG = LoggerFactory.getLogger(SecurityInfrastructureConfig.class);

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  SigningKeyStore signingKeyStore(
      VaultTemplate vault,
      LibertyVaultProperties vaultProperties,
      LibertySecurityProperties properties) {
    if (properties.keys().store() == LibertySecurityProperties.Keys.Store.MEMORY) {
      LOG.warn("Chaves de assinatura em MEMÓRIA: apenas para desenvolvimento e testes");
      return new InMemorySigningKeyStore();
    }
    return new VaultSigningKeyStore(vault, vaultProperties.mount(), properties.keys().vaultPath());
  }

  @Bean
  SigningKeyRing signingKeyRing(
      SigningKeyStore store, Clock clock, LibertySecurityProperties properties) {
    return new SigningKeyRing(
        store, clock, properties.keys().rotationPeriod(), properties.keys().retirementGrace());
  }

  @Bean
  ApiClientDirectory apiClientDirectory(
      ApiClientRepository clients,
      TenantRepository tenants,
      PlatformTransactionManager transactionManager,
      Clock clock,
      LibertySecurityProperties properties) {
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    return new JpaApiClientDirectory(
        clients, tenants, readOnly, clock, properties.clientCacheTtl());
  }
}
