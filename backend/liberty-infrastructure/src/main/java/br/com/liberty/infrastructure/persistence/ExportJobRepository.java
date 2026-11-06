package br.com.liberty.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ExportJobRepository extends Repository<ExportJobEntity, Long> {

  /**
   * Não existe `findById` sem tenant: baixar a exportação de outro tenant é impossível por desenho.
   */
  Optional<ExportJobEntity> findByIdAndTenantId(Long id, Long tenantId);

  ExportJobEntity save(ExportJobEntity job);
}
