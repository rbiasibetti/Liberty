package br.com.liberty;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Backup e restauração do banco central, os mesmos comandos do runbook {@code restaurar-core.md}:
 * backup completo com checksum, verificação, restauração sob outro nome e conferência de dados,
 * esquema e migrações aplicadas.
 */
class BackupRestoreTest {

  private static CoreTestDatabase.Scratch db;
  private static String restored;
  private static String file;

  @BeforeAll
  static void migrate() {
    db = CoreTestDatabase.create();
    Flyway.configure()
        .dataSource(db.url(), db.user(), db.password())
        .locations("classpath:db/migration/core")
        .table("int_flyway_schema_history")
        .load()
        .migrate();
  }

  @AfterAll
  static void clean() throws SQLException {
    if (restored != null) {
      exec(
          "master",
          "ALTER DATABASE ["
              + restored
              + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ["
              + restored
              + "]");
    }
    CoreTestDatabase.dropAll();
  }

  private static String url(String database) {
    return db.url().replaceAll("databaseName=[^;]+", "databaseName=" + database);
  }

  private static String scalar(String database, String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(url(database), db.user(), db.password());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static void exec(String database, String sql) throws SQLException {
    try (Connection c = DriverManager.getConnection(url(database), db.user(), db.password());
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  @Test
  void aCompleteBackupRestoresTheCoreWithDataSchemaAndMigrationHistory() throws SQLException {
    exec(db.name(), "INSERT INTO int_tenant (slug, name) VALUES ('acme', 'Acme')");
    String dir =
        scalar(
            "master", "SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(400))");
    assertThat(dir).as("o servidor precisa ter um diretório de backup").isNotBlank();
    String separator = dir.contains("\\") ? "\\" : "/";
    file =
        dir
            + (dir.endsWith(separator) ? "" : separator)
            + db.name()
            + "-"
            + UUID.randomUUID()
            + ".bak";

    exec(
        "master",
        "BACKUP DATABASE ["
            + db.name()
            + "] TO DISK = N'"
            + file
            + "' WITH CHECKSUM, INIT, FORMAT");
    exec("master", "RESTORE VERIFYONLY FROM DISK = N'" + file + "' WITH CHECKSUM");

    restored = db.name() + "_rst";
    String logicalData =
        scalar(
            "master",
            "SELECT TOP 1 name FROM sys.master_files WHERE database_id = DB_ID('"
                + db.name()
                + "') AND type = 0");
    String logicalLog =
        scalar(
            "master",
            "SELECT TOP 1 name FROM sys.master_files WHERE database_id = DB_ID('"
                + db.name()
                + "') AND type = 1");
    String dataPath =
        scalar(
            "master",
            "SELECT TOP 1 physical_name FROM sys.master_files WHERE database_id = DB_ID('"
                + db.name()
                + "') AND type = 0");
    String logPath =
        scalar(
            "master",
            "SELECT TOP 1 physical_name FROM sys.master_files WHERE database_id = DB_ID('"
                + db.name()
                + "') AND type = 1");
    exec(
        "master",
        "RESTORE DATABASE ["
            + restored
            + "] FROM DISK = N'"
            + file
            + "' WITH CHECKSUM, RECOVERY,"
            + " MOVE N'"
            + logicalData
            + "' TO N'"
            + dataPath.replace(db.name(), restored)
            + "',"
            + " MOVE N'"
            + logicalLog
            + "' TO N'"
            + logPath.replace(db.name(), restored)
            + "'");

    assertThat(scalar(restored, "SELECT COUNT(*) FROM int_tenant WHERE slug = 'acme'"))
        .isEqualTo("1");
    assertThat(scalar(restored, "SELECT COUNT(*) FROM int_flyway_schema_history WHERE success = 1"))
        .isEqualTo(
            scalar(db.name(), "SELECT COUNT(*) FROM int_flyway_schema_history WHERE success = 1"));
    assertThat(scalar(restored, "SELECT COUNT(*) FROM sys.tables WHERE name LIKE 'int[_]%'"))
        .isEqualTo(scalar(db.name(), "SELECT COUNT(*) FROM sys.tables WHERE name LIKE 'int[_]%'"));
    assertThat(scalar(restored, "SELECT COUNT(*) FROM sys.triggers WHERE parent_class = 1"))
        .as("gatilhos de imutabilidade restaurados")
        .isEqualTo(scalar(db.name(), "SELECT COUNT(*) FROM sys.triggers WHERE parent_class = 1"));
    assertThat(
            scalar(
                restored,
                "SELECT COUNT(*) FROM sys.partition_functions WHERE name = 'pf_audit_month'"))
        .isEqualTo("1");
  }
}
