package br.com.liberty.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Somente leitura: toda consulta é escopada pelo tenant. */
public interface AuditExecutionRepository extends Repository<AuditExecutionEntity, Long> {

  Page<AuditExecutionEntity> findByTenantIdAndCreatedAtBetween(
      Long tenantId, Instant from, Instant to, Pageable pageable);

  List<AuditExecutionEntity> findByTenantIdAndRequestId(Long tenantId, String requestId);
}
