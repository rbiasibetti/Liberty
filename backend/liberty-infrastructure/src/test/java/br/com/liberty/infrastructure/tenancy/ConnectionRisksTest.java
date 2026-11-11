package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Privilégios excessivos do usuário do banco do cliente, vistos pelo checklist de onboarding. */
class ConnectionRisksTest {

  private static final CoreTestDatabase.Scratch DB = CoreTestDatabase.create();
  private static final String LOGIN =
      "liberty_limited_" + UUID.randomUUID().toString().substring(0, 8);

  @AfterAll
  static void drop() throws SQLException {
    try (Connection c = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
        Statement st = c.createStatement()) {
      st.execute("DROP USER IF EXISTS [" + LOGIN + "]");
    }
    try (Connection c =
            DriverManager.getConnection(
                DB.url().replaceAll("databaseName=[^;]+", "databaseName=master"),
                DB.user(),
                DB.password());
        Statement st = c.createStatement()) {
      st.execute("DROP LOGIN [" + LOGIN + "]");
    }
    CoreTestDatabase.dropAll();
  }

  @Test
  void anAdministrativeUserIsFlaggedAndALimitedOneIsNot() throws Exception {
    assertThat(ConnectionTester.risks(DB.url(), DB.user(), DB.password())).contains("sysadmin");

    String password = "Aa1!" + UUID.randomUUID();
    try (Connection c =
            DriverManager.getConnection(
                DB.url().replaceAll("databaseName=[^;]+", "databaseName=master"),
                DB.user(),
                DB.password());
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE LOGIN [" + LOGIN + "] WITH PASSWORD = '" + password + "', CHECK_POLICY = OFF");
    }
    try (Connection c = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
        Statement st = c.createStatement()) {
      st.execute("CREATE USER [" + LOGIN + "] FOR LOGIN [" + LOGIN + "]");
      st.execute("ALTER ROLE db_datareader ADD MEMBER [" + LOGIN + "]");
      st.execute("ALTER ROLE db_datawriter ADD MEMBER [" + LOGIN + "]");
    }

    assertThat(ConnectionTester.risks(DB.url(), LOGIN, password)).isEmpty();

    try (Connection c = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
        Statement st = c.createStatement()) {
      st.execute("ALTER ROLE db_owner ADD MEMBER [" + LOGIN + "]");
    }
    assertThat(ConnectionTester.risks(DB.url(), LOGIN, password)).containsExactly("db_owner");
  }

  @Test
  void whenItCannotConnectItSaysItCouldNotCheck() {
    assertThat(ConnectionTester.risks(DB.url(), DB.user(), "senha-errada")).isNull();
    assertThat(ConnectionTester.risks("jdbc:postgresql://h/db", "u", "p")).isNull();
  }
}
