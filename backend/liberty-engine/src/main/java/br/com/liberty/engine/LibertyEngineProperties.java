package br.com.liberty.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ajustes do motor: intervalo de atualização das rotas publicadas e prazo das consultas ao banco do
 * tenant.
 */
@ConfigurationProperties("liberty.engine")
public record LibertyEngineProperties(
    @DefaultValue("5s") Duration routesRefreshInterval,
    @DefaultValue("30s") Duration queryTimeout,
    @DefaultValue("60s") Duration writeTimeout,
    @DefaultValue("50000") int maxWriteRows,
    @DefaultValue("24h") Duration idempotencyTtl) {}
