package br.com.liberty.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface IntegrationRepository extends Repository<IntegrationEntity, Long> {

  Optional<IntegrationEntity> findByIdAndTenantId(Long id, Long tenantId);

  Optional<IntegrationEntity> findByTenantIdAndIntegrationKey(Long tenantId, String integrationKey);

  Optional<IntegrationEntity> findByTenantIdAndHttpMethodAndRoutePattern(
      Long tenantId, String httpMethod, String routePattern);

  List<IntegrationEntity> findByTenantIdAndArchivedFalseOrderByIntegrationKey(Long tenantId);

  IntegrationEntity save(IntegrationEntity integration);
}
