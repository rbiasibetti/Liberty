package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface PublicationRepository extends Repository<PublicationEntity, Long> {

  Optional<PublicationEntity> findByTenantIdAndIntegrationIdAndEnvironmentAndActiveTrue(
      Long tenantId, Long integrationId, EnvironmentKind environment);

  List<PublicationEntity> findByTenantIdAndEnvironmentAndActiveTrue(
      Long tenantId, EnvironmentKind environment);

  PublicationEntity save(PublicationEntity publication);
}
