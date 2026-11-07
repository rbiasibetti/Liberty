package br.com.liberty.infrastructure.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskDecorator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.KubernetesAuthentication;
import org.springframework.vault.authentication.KubernetesAuthenticationOptions;
import org.springframework.vault.authentication.KubernetesServiceAccountTokenFile;
import org.springframework.vault.authentication.SimpleSessionManager;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

/**
 * Dois mundos de banco (ADR 0006): o core é o único DataSource bean (primário, usado por JPA e
 * Flyway); o roteado por tenant fica escondido atrás do JdbcTemplate e do gerenciador de transação
 * de tenant, que só o motor usa.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  LibertyVaultProperties.class,
  TenantPersistenceConfig.TenancyProperties.class
})
public class TenantPersistenceConfig {

  /** Tempo sem uso após o qual o pool de um tenant é fechado. */
  @ConfigurationProperties("liberty.tenancy")
  public record TenancyProperties(Duration poolIdleTtl) {
    public TenancyProperties {
      poolIdleTtl = poolIdleTtl == null ? Duration.ofMinutes(30) : poolIdleTtl;
    }
  }

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource")
  DataSourceProperties coreDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource coreDataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  /**
   * O gerenciador de transação do tenant impede a autoconfiguração do JPA, então o do core é
   * declarado aqui e é o primário: `@Transactional` sem qualificador sempre usa o banco central.
   */
  @Bean
  @Primary
  PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    return new JpaTransactionManager(entityManagerFactory);
  }

  @Bean
  VaultTemplate vault(LibertyVaultProperties properties) {
    return vaultTemplate(properties);
  }

  @Bean
  SecretProvider secretProvider(VaultTemplate vault, LibertyVaultProperties properties) {
    return new VaultSecretProvider(vault, properties.mount());
  }

  static VaultTemplate vaultTemplate(LibertyVaultProperties properties) {
    VaultEndpoint endpoint = VaultEndpoint.from(properties.uri());
    VaultClient client = VaultClient.builder().endpoint(endpoint).build();
    ClientAuthentication authentication =
        switch (properties.authentication()) {
          case TOKEN -> new TokenAuthentication(properties.token());
          case KUBERNETES ->
              new KubernetesAuthentication(
                  KubernetesAuthenticationOptions.builder()
                      .role(properties.kubernetesRole())
                      .jwtSupplier(
                          new KubernetesServiceAccountTokenFile(properties.kubernetesJwtPath()))
                      .build(),
                  client);
        };
    return new VaultTemplate(client, new SimpleSessionManager(authentication));
  }

  @Bean
  TenantPoolRegistry tenantPoolRegistry(
      TenantConnectionResolver resolver, SecretProvider secrets, TenancyProperties properties) {
    return new TenantPoolRegistry(resolver, secrets, properties.poolIdleTtl());
  }

  @Bean("tenantJdbcTemplate")
  NamedParameterJdbcTemplate tenantJdbcTemplate(TenantPoolRegistry registry) {
    return new NamedParameterJdbcTemplate(new TenantRoutingDataSource(registry));
  }

  @Bean("tenantTransactionManager")
  PlatformTransactionManager tenantTransactionManager(
      @Qualifier("tenantJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
    DataSource routing = jdbc.getJdbcTemplate().getDataSource();
    return new DataSourceTransactionManager(routing);
  }

  @Bean("tenantTransactionTemplate")
  TransactionTemplate tenantTransactionTemplate(
      @Qualifier("tenantTransactionManager") PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  TaskDecorator tenantContextTaskDecorator() {
    return new TenantContextTaskDecorator();
  }

  /**
   * Executor das tarefas assíncronas da aplicação: propaga o tenant e o limpa ao fim de cada
   * tarefa.
   */
  @Bean(name = "applicationTaskExecutor")
  ThreadPoolTaskExecutor applicationTaskExecutor(TaskDecorator tenantContextTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("app-task-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(200);
    executor.setTaskDecorator(tenantContextTaskDecorator);
    return executor;
  }
}
