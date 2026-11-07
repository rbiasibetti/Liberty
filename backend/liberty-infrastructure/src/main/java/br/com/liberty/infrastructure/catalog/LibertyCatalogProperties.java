package br.com.liberty.infrastructure.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tempos de cache do catálogo dos tenants. */
@ConfigurationProperties("liberty.catalog")
public record LibertyCatalogProperties(
    @DefaultValue("5m") Duration ttl, @DefaultValue("30s") Duration missTtl) {}
