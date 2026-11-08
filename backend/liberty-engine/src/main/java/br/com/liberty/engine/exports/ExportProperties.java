package br.com.liberty.engine.exports;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Ajustes das exportações (ADR 0036). */
@ConfigurationProperties("liberty.exports")
public record ExportProperties(
    /** Pasta do armazenamento local; em produção, um volume dedicado e com backup. */
    @DefaultValue("./data/exports") String directory,
    /**
     * Chave HMAC das URLs assinadas (mínimo 32 caracteres). Vazia, cada instância gera uma ao
     * subir: serve para desenvolvimento, mas links não valem em outra instância nem depois de
     * reiniciar.
     */
    String signingKey,
    @DefaultValue("10m") Duration linkTtl,
    @DefaultValue("24h") Duration retention,
    @DefaultValue("100000") int maxRows,
    @DefaultValue("104857600") long maxBytes,
    @DefaultValue("500") int pageSize,
    @DefaultValue("3") int maxActivePerTenant) {}
