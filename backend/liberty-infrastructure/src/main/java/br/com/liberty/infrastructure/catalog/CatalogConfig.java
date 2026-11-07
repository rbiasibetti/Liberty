package br.com.liberty.infrastructure.catalog;

import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LibertyCatalogProperties.class)
public class CatalogConfig {

  @Bean
  CatalogIntrospector catalogIntrospector(TenantPoolRegistry registry) {
    return new JdbcCatalogIntrospector(registry);
  }

  @Bean
  TenantCatalog tenantCatalog(
      CatalogIntrospector introspector, LibertyCatalogProperties properties) {
    return new TenantCatalog(introspector, properties.ttl(), properties.missTtl());
  }
}
