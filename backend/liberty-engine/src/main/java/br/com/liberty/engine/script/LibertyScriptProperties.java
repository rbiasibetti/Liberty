package br.com.liberty.engine.script;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ajustes dos scripts (ADR 0017). A memória total do contêiner precisa cobrir {@code poolSize ×
 * (heap + 100 MB)}.
 */
@ConfigurationProperties("liberty.scripts")
public record LibertyScriptProperties(
    @DefaultValue("2") int poolSize,
    @DefaultValue("128m") String heap,
    @DefaultValue("2s") Duration timeout,
    @DefaultValue("5000000") long maxStatements,
    @DefaultValue("500ms") Duration queueWait,
    @DefaultValue("1s") Duration killGrace,
    @DefaultValue("4") int tenantConcurrency,
    @DefaultValue("1000") int recycleRuns,
    @DefaultValue("10m") Duration recycleAge,
    @DefaultValue("20") int maxHostCalls,
    String workerClasspath) {}
