package br.com.liberty.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Catálogo de tenants. Consultas por slug são globais por natureza (resolução do tenant). */
public interface TenantRepository extends Repository<TenantEntity, Long> {

  Optional<TenantEntity> findBySlug(String slug);

  Optional<TenantEntity> findById(Long id);

  TenantEntity save(TenantEntity tenant);

  java.util.List<TenantEntity> findAllByOrderBySlugAsc();
}
