package br.com.liberty.infrastructure.tenancy;

import br.com.liberty.domain.tenancy.TenantScope;
import java.util.Optional;

/**
 * Descobre como conectar ao banco de um tenant; vazio se o tenant ou a conexão não estiver ativo.
 */
public interface TenantConnectionResolver {

  Optional<TenantConnectionInfo> resolve(TenantScope scope);
}
