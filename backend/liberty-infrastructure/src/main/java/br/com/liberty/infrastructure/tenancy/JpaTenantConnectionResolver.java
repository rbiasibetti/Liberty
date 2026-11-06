package br.com.liberty.infrastructure.tenancy;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolve a conexão pelo catálogo do banco central, ignorando tenants e conexões inativos. */
@Component
class JpaTenantConnectionResolver implements TenantConnectionResolver {

  private static final String ACTIVE = "ACTIVE";

  private final TenantRepository tenants;
  private final TenantConnectionRepository connections;

  JpaTenantConnectionResolver(TenantRepository tenants, TenantConnectionRepository connections) {
    this.tenants = tenants;
    this.connections = connections;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantConnectionInfo> resolve(TenantScope scope) {
    return tenants
        .findBySlug(scope.tenant().value())
        .filter(tenant -> ACTIVE.equals(tenant.getStatus()))
        .flatMap(
            tenant -> connections.findByTenantIdAndEnvironment(tenant.getId(), scope.environment()))
        .filter(connection -> ACTIVE.equals(connection.getStatus()))
        .map(JpaTenantConnectionResolver::toInfo);
  }

  private static TenantConnectionInfo toInfo(TenantConnectionEntity c) {
    return new TenantConnectionInfo(
        c.getJdbcUrl(), c.getDbUsername(), c.getSecretRef(), c.getPoolMax());
  }
}
