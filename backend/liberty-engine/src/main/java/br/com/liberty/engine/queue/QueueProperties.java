package br.com.liberty.engine.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Ajustes das filas por tenant (ADR 0035). */
@ConfigurationProperties("liberty.queue")
public record QueueProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("500ms") Duration pollInterval,
    /** Mensagens em processamento em toda a instância. */
    @DefaultValue("16") int workers,
    /** Mensagens em processamento por tenant: o bulkhead. Sempre menor que {@code workers}. */
    @DefaultValue("2") int tenantConcurrency,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("2s") Duration baseBackoff,
    @DefaultValue("10m") Duration maxBackoff,
    @DefaultValue("60s") Duration lease,
    @DefaultValue("65536") int maxPayloadBytes,
    @DefaultValue("10000") int maxPendingPerTenant,
    @DefaultValue("10") int maxPublishPerExecution,
    @DefaultValue("30d") Duration deadRetention) {}
