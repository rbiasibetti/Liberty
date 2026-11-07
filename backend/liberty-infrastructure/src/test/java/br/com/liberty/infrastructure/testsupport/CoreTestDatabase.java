package br.com.liberty.infrastructure.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/**
 * Banco SQL Server descartável para testes: um banco novo por chamada, apagado ao fim da JVM. Usa o
 * servidor de LIBERTY_TEST_DB_HOST/PORT/USER/PASSWORD quando definido; senão sobe um container
 * (Testcontainers, exige Docker ou Podman compatível).
 */
public final class CoreTestDatabase {

  /** Banco recém-criado; usuário e senha têm permissão de DDL. */
  public record Scratch(String url, String user, String password, String name) {}

  private record Admin(String jdbcBase, String user, String password) {}

  private static final String IMAGE = "mcr.microsoft.com/mssql/server:2022-CU27-ubuntu-22.04";
  private static final Object CREATE_LOCK = new Object();
  private static final List<String> CREATED = new ArrayList<>();
  private static Admin admin;

  private CoreTestDatabase() {}

  public static Scratch create() {
    initAdmin();
    String name =
        "liberty_core_test_"
            + HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 12);
    // CREATE DATABASE bloqueia o banco model: criações simultâneas colidem, então são serializadas
    synchronized (CREATE_LOCK) {
      execute("master", "CREATE DATABASE [" + name + "] COLLATE Latin1_General_100_CI_AS_SC_UTF8");
    }
    synchronized (CREATED) {
      CREATED.add(name);
    }
    return new Scratch(url(name), admin.user(), admin.password(), name);
  }

  private static synchronized void initAdmin() {
    if (admin == null) {
      admin = connectToServer();
      Runtime.getRuntime().addShutdownHook(new Thread(CoreTestDatabase::dropAll));
    }
  }

  private static Admin connectToServer() {
    String password = System.getenv("LIBERTY_TEST_DB_PASSWORD");
    if (password != null && !password.isBlank()) {
      String host = System.getenv().getOrDefault("LIBERTY_TEST_DB_HOST", "127.0.0.1");
      String port = System.getenv().getOrDefault("LIBERTY_TEST_DB_PORT", "1433");
      String user = System.getenv().getOrDefault("LIBERTY_TEST_DB_USER", "sa");
      return new Admin("jdbc:sqlserver://" + host + ":" + port, user, password);
    }
    try {
      MSSQLServerContainer container = new MSSQLServerContainer(IMAGE).acceptLicense();
      container.start();
      return new Admin(
          "jdbc:sqlserver://" + container.getHost() + ":" + container.getMappedPort(1433),
          container.getUsername(),
          container.getPassword());
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Sem banco para os testes: defina LIBERTY_TEST_DB_PASSWORD (e HOST/PORT/USER) ou instale o Docker. "
              + "Dica: `set -a; . ./.env.local; set +a` antes do Maven.",
          e);
    }
  }

  private static String url(String database) {
    return admin.jdbcBase()
        + ";databaseName="
        + database
        + ";encrypt=true;trustServerCertificate=true";
  }

  private static void execute(String database, String sql) {
    try (Connection connection =
            DriverManager.getConnection(url(database), admin.user(), admin.password());
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("Falha ao executar: " + sql, e);
    }
  }

  /**
   * Apaga todos os bancos criados até aqui, em paralelo (50 bancos em série estouram o prazo de
   * saída da JVM).
   */
  public static void dropAll() {
    List<String> names;
    synchronized (CREATED) {
      names = List.copyOf(CREATED);
      CREATED.clear();
    }
    names.parallelStream()
        .forEach(
            name -> {
              try {
                execute(
                    "master",
                    "ALTER DATABASE ["
                        + name
                        + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ["
                        + name
                        + "]");
              } catch (IllegalStateException ignored) {
                // melhor esforço: o banco de teste é descartável
              }
            });
  }
}
