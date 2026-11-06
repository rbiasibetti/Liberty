package br.com.liberty.infrastructure.tenancy;

import br.com.liberty.domain.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * DataSource que entrega conexões do banco do tenant do contexto atual. Não tem destino padrão: sem
 * tenant no contexto lança {@link IllegalStateException}. Não é um bean, para ficar fora dos health
 * checks e da autoconfiguração; só o motor o enxerga pelo {@code NamedParameterJdbcTemplate} do
 * tenant.
 */
public final class TenantRoutingDataSource extends AbstractDataSource {

  private final TenantPoolRegistry registry;

  public TenantRoutingDataSource(TenantPoolRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return registry.pool(TenantContext.require()).getConnection();
  }

  @Override
  public Connection getConnection(String username, String password) {
    throw new UnsupportedOperationException("Credenciais por conexão não são suportadas");
  }
}
