package br.com.liberty.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Busca por `client_id` é global: é a autenticação que descobre o tenant do cliente. */
public interface ApiClientRepository extends Repository<ApiClientEntity, Long> {

  Optional<ApiClientEntity> findByClientId(String clientId);

  List<ApiClientEntity> findByTenantIdOrderByClientId(Long tenantId);

  ApiClientEntity save(ApiClientEntity client);
}
