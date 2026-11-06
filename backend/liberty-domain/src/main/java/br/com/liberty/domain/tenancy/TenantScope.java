package br.com.liberty.domain.tenancy;

import java.util.Objects;

/**
 * Tenant e ambiente em que uma requisição ou tarefa executa; define qual banco de cliente é usado.
 */
public record TenantScope(TenantId tenant, EnvironmentKind environment) {

  public TenantScope {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(environment, "environment");
  }

  @Override
  public String toString() {
    return tenant + "/" + environment;
  }
}
