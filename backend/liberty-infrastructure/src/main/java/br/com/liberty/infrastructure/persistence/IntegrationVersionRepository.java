package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.integration.VersionState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface IntegrationVersionRepository extends Repository<IntegrationVersionEntity, Long> {

  Optional<IntegrationVersionEntity> findByIdAndTenantId(Long id, Long tenantId);

  List<IntegrationVersionEntity> findByIntegrationIdAndTenantIdOrderByVersionNoDesc(
      Long integrationId, Long tenantId);

  Optional<IntegrationVersionEntity> findByIntegrationIdAndTenantIdAndState(
      Long integrationId, Long tenantId, VersionState state);

  /**
   * Maior número de versão da integração, com bloqueio que serializa criações concorrentes (a
   * restrição única é a segunda barreira). Deve rodar dentro da transação que insere a versão.
   */
  @Query(
      value =
          "SELECT COALESCE(MAX(version_no), 0) FROM int_integration_version"
              + " WITH (UPDLOCK, HOLDLOCK) WHERE integration_id = :integrationId",
      nativeQuery = true)
  int lockAndGetMaxVersionNo(@Param("integrationId") Long integrationId);

  IntegrationVersionEntity save(IntegrationVersionEntity version);
}
