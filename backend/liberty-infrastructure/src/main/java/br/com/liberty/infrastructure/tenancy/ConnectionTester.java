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

  /**
   * Privilégios do usuário do banco do cliente que a plataforma não quer ver: quem administra o
   * servidor ou o banco (ou cria objetos e gerencia segurança) pode, por exemplo, habilitar {@code
   * xp_cmdshell} ou criar gatilhos com efeito externo. Devolve os papéis encontrados; vazio é o
   * esperado. Falha de conexão devolve {@code null} (não foi possível verificar).
   */
  public static java.util.List<String> risks(String url, String user, String password) {
    try {
      JdbcUrlPolicy.check(url);
    } catch (TenantUnavailableException e) {
      return null;
    }
    Properties properties = new Properties();
    properties.setProperty("user", user);
    properties.setProperty("password", password);
    properties.setProperty("loginTimeout", Integer.toString(TIMEOUT_SECONDS));
    String sql =
        "SELECT IS_SRVROLEMEMBER('sysadmin'), IS_SRVROLEMEMBER('securityadmin'),"
            + " IS_SRVROLEMEMBER('serveradmin'), IS_MEMBER('db_owner'), IS_MEMBER('db_ddladmin'),"
            + " IS_MEMBER('db_securityadmin')";
    String[] names = {
      "sysadmin", "securityadmin", "serveradmin", "db_owner", "db_ddladmin", "db_securityadmin"
    };
    try (Connection connection = DriverManager.getConnection(url, properties);
        Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(TIMEOUT_SECONDS);
      try (ResultSet rs = statement.executeQuery(sql)) {
        rs.next();
        java.util.List<String> found = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
          if (rs.getInt(i + 1) == 1) {
            found.add(names[i]);
          }
        }
        return found;
      }
    } catch (SQLException e) {
      return null;
    }
  }

  private static long millis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
