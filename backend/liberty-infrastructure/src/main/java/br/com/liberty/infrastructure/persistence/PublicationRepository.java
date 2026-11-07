package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface PublicationRepository extends Repository<PublicationEntity, Long> {

  Optional<PublicationEntity> findByTenantIdAndIntegrationIdAndEnvironmentAndActiveTrue(
      Long tenantId, Long integrationId, EnvironmentKind environment);

  List<PublicationEntity> findByTenantIdAndEnvironmentAndActiveTrue(
      Long tenantId, EnvironmentKind environment);

  /**
   * Integrações servidas no ambiente: publicação ativa, integração não arquivada, com o snapshot
   * publicado.
   */
  @Query(
      "select new br.com.liberty.infrastructure.persistence.PublishedRow("
          + "p.id, i.id, i.integrationKey, i.httpMethod, i.routePattern, v.id, v.versionNo, v.snapshot) "
          + "from PublicationEntity p "
          + "join IntegrationEntity i on i.id = p.integrationId "
          + "join IntegrationVersionEntity v on v.id = p.versionId "
          + "where p.tenantId = :tenantId and p.environment = :environment and p.active = true "
          + "and i.archived = false and i.tenantId = :tenantId and v.tenantId = :tenantId "
          + "order by i.id")
  List<PublishedRow> findServed(
      @Param("tenantId") Long tenantId, @Param("environment") EnvironmentKind environment);

  /**
   * Marca barata do conjunto de publicações ativas: muda quando se publica, despublica ou faz
   * rollback.
   */
  @Query(
      "select concat(count(p), ':', coalesce(max(p.id), 0), ':', coalesce(sum(p.id), 0)) "
          + "from PublicationEntity p where p.tenantId = :tenantId and p.environment = :environment and p.active = true")
  String servedToken(
      @Param("tenantId") Long tenantId, @Param("environment") EnvironmentKind environment);

  /** Histórico de publicações da integração no ambiente, da mais recente para a mais antiga. */
  List<PublicationEntity> findByTenantIdAndIntegrationIdAndEnvironmentOrderByIdDesc(
      Long tenantId, Long integrationId, EnvironmentKind environment);

  PublicationEntity save(PublicationEntity publication);
}
