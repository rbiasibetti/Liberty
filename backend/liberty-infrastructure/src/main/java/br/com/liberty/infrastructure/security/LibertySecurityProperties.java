package br.com.liberty.infrastructure.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuração de autenticação e autorização (ADR 0021). */
@ConfigurationProperties("liberty.security")
public record LibertySecurityProperties(
    /* Emissor dos tokens de cliente (esta aplicação); aparece no claim iss. */
    URI issuer,
    @DefaultValue("liberty-api") String audience,
    @DefaultValue("15m") Duration tokenTtl,
    @DefaultValue("15s") Duration clientCacheTtl,
    @DefaultValue Keys keys,
    @DefaultValue Oidc oidc,
    @DefaultValue List<String> allowedOrigins) {

  /**
   * Onde ficam as chaves de assinatura: o cofre (padrão) ou memória, só para desenvolvimento e
   * testes.
   */
  public record Keys(
      @DefaultValue("VAULT") Store store,
      @DefaultValue("liberty/jwt/keys") String vaultPath,
      @DefaultValue("90d") Duration rotationPeriod,
      @DefaultValue("24h") Duration retirementGrace) {

    public enum Store {
      VAULT,
      MEMORY
    }
  }

  /** Provedor OIDC dos usuários da interface (Keycloak, Entra ID etc.). */
  public record Oidc(
      URI issuer,
      @DefaultValue("liberty-frontend") String clientId,
      @DefaultValue("liberty-platform-admin") String platformAdminRole) {}
}
