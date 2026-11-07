package br.com.liberty.infrastructure.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Teste de conexão de um cadastro de tenant: aplica a mesma política de URL dos pools (só SQL
 * Server, com criptografia, sem propriedades perigosas), abre uma conexão com prazo curto e roda
 * {@code SELECT 1}. O resultado nunca carrega mensagem do driver (pode citar host, usuário ou
 * senha).
 */
public final class ConnectionTester {

  /** Resultado do teste. */
  public record Result(boolean ok, long millis, String message) {}

  private static final int TIMEOUT_SECONDS = 5;

  private ConnectionTester() {}

  /** Valida a URL com a política dos pools; falha com {@link TenantUnavailableException}. */
  public static void validateUrl(String url) {
    JdbcUrlPolicy.check(url);
  }

  public static Result test(String url, String user, String password) {
    long start = System.nanoTime();
    try {
      JdbcUrlPolicy.check(url);
    } catch (TenantUnavailableException e) {
      return new Result(false, 0, e.getMessage());
    }
    Properties properties = new Properties();
    properties.setProperty("user", user);
    properties.setProperty("password", password);
    properties.setProperty("loginTimeout", Integer.toString(TIMEOUT_SECONDS));
    try (Connection connection = DriverManager.getConnection(url, properties);
        Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = statement.executeQuery("SELECT 1")) {
        rs.next();
      }
      return new Result(true, millis(start), "Conexão estabelecida");
    } catch (SQLException e) {
      return new Result(false, millis(start), "Não foi possível conectar com os dados informados");
    }
  }

  private static long millis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
