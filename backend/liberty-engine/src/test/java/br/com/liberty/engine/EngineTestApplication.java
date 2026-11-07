package br.com.liberty.engine;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Configuração mínima para os testes de fatia do motor (a aplicação real fica em liberty-app). */
@SpringBootApplication
@EntityScan("br.com.liberty.infrastructure.persistence")
@EnableJpaRepositories("br.com.liberty.infrastructure.persistence")
class EngineTestApplication {}
