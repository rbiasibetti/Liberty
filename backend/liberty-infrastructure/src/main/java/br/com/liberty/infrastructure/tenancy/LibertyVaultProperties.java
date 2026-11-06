package br.com.liberty.infrastructure.tenancy;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Acesso ao cofre (Vault KV v2). Em produção usa a autenticação Kubernetes; o token estático é só
 * para desenvolvimento local.
 */
@ConfigurationProperties("liberty.vault")
public record LibertyVaultProperties(
    URI uri,
    @DefaultValue("secret") String mount,
    @DefaultValue("TOKEN") Authentication authentication,
    String token,
    String kubernetesRole,
    @DefaultValue("/var/run/secrets/kubernetes.io/serviceaccount/token") String kubernetesJwtPath) {

  public enum Authentication {
    TOKEN,
    KUBERNETES
  }

  @Override
  public String toString() {
    return "LibertyVaultProperties[uri="
        + uri
        + ", mount="
        + mount
        + ", authentication="
        + authentication
        + "]";
  }
}
