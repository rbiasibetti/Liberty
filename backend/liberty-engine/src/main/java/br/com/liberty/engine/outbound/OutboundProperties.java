package br.com.liberty.engine.outbound;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites do {@code http.fetch} dos scripts (ADR 0034). Não existe chave que desligue a defesa
 * contra SSRF: só os limites de volume e de tempo são ajustáveis.
 */
@ConfigurationProperties("liberty.outbound")
public record OutboundProperties(
    @DefaultValue("5s") Duration timeout,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("1048576") int maxResponseBytes,
    @DefaultValue("262144") int maxRequestBytes,
    @DefaultValue("5") int maxCallsPerExecution,
    @DefaultValue("32") int maxConnections) {}
