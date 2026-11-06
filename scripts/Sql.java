import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executa um script SQL no SQL Server (lotes separados por "GO"), substituindo ${VAR} por variáveis de
 * ambiente. Uso: java -cp mssql-jdbc.jar scripts/Sql.java <host> <porta> <banco> <usuário> <arquivo.sql>
 * A senha vem da variável SQL_PASSWORD e nunca aparece na linha de comando.
 */
public final class Sql {

  private static final Pattern VAR = Pattern.compile("\\$\\{([A-Z0-9_]+)}");

  private Sql() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      throw new IllegalArgumentException("uso: Sql <host> <porta> <banco> <usuario> <arquivo>");
    }
    String url =
        "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=true;trustServerCertificate=true"
            .formatted(args[0], args[1], args[2]);
    String password = System.getenv("SQL_PASSWORD");
    if (password == null) {
      throw new IllegalStateException("defina SQL_PASSWORD");
    }
    String script = substitute(Files.readString(Path.of(args[4])));
    try (Connection connection = DriverManager.getConnection(url, args[3], password);
        Statement statement = connection.createStatement()) {
      for (String batch : batches(script)) {
        try {
          statement.execute(batch);
        } catch (SQLException e) {
          System.err.println("Falha em " + args[4] + ": " + e.getMessage());
          System.exit(1);
        }
      }
    }
  }

  private static String substitute(String text) {
    Matcher matcher = VAR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null) {
        throw new IllegalStateException("variável ausente: " + matcher.group(1));
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static List<String> batches(String script) {
    List<String> result = new ArrayList<>();
    for (String batch : script.split("(?im)^\\s*GO\\s*$")) {
      if (!batch.isBlank()) {
        result.add(batch);
      }
    }
    return result;
  }
}
