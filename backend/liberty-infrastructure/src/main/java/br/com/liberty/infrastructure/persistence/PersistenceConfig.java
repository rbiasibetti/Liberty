package br.com.liberty.infrastructure.persistence;

import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ajustes do Hibernate válidos em qualquer aplicação que use este módulo. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

  /** `Instant` vira DATETIME2 em UTC (o padrão do dialeto SQL Server seria DATETIMEOFFSET). */
  @Bean
  HibernatePropertiesCustomizer instantAsUtcDatetime2() {
    return properties -> {
      properties.put("hibernate.type.preferred_instant_jdbc_type", "TIMESTAMP");
      properties.put("hibernate.jdbc.time_zone", "UTC");
    };
  }
}
