package br.com.liberty.engine.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fila e lote da gravação de auditoria. */
@ConfigurationProperties("liberty.audit")
public record LibertyAuditProperties(
    @DefaultValue("10000") int queueCapacity,
    @DefaultValue("500") int batchSize,
    @DefaultValue("200ms") Duration flushInterval,
    @DefaultValue("13") int retentionMonths,
    @DefaultValue("3") int monthsAhead) {}
