package br.com.liberty.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TenantHttpHostRepository
    extends Repository<TenantHttpHostEntity, TenantHttpHostEntity.Key> {

  List<TenantHttpHostEntity> findByIdTenantIdOrderByIdHost(Long tenantId);

  Optional<TenantHttpHostEntity> findById(TenantHttpHostEntity.Key key);

  TenantHttpHostEntity save(TenantHttpHostEntity host);

  void delete(TenantHttpHostEntity host);

  /** O host está liberado para o tenant de slug dado? */
  @Query(
      "select count(h) > 0 from TenantHttpHostEntity h, TenantEntity t"
          + " where t.slug = :slug and h.id.tenantId = t.id and h.id.host = :host")
  boolean allowed(@Param("slug") String slug, @Param("host") String host);
}
