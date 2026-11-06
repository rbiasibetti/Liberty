package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface TenantConnectionRepository extends Repository<TenantConnectionEntity, Long> {

  Optional<TenantConnectionEntity> findByTenantIdAndEnvironment(
      Long tenantId, EnvironmentKind environment);

  TenantConnectionEntity save(TenantConnectionEntity connection);
}
