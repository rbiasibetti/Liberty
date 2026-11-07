package br.com.liberty.engine.write;

import br.com.liberty.engine.problem.ProblemException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * Traduz erros do SQL Server pelo número do erro (nunca pelo texto, que varia com o idioma do
 * servidor) em respostas genéricas: nome de restrição, de tabela ou valor do cliente não vazam.
 */
final class SqlErrors {

  private SqlErrors() {}

  /** Deadlock escolhido como vítima (1205): a transação foi desfeita e pode ser repetida. */
  static boolean isDeadlock(Throwable error) {
    for (int code : codes(error)) {
      if (code == 1205) {
        return true;
      }
    }
    return false;
  }

  static Optional<ProblemException> translate(Throwable error) {
    for (int code : codes(error)) {
      switch (code) {
        case 2627, 2601 -> {
          return Optional.of(
              new ProblemException(
                  409, "conflict", "Conflito", "Já existe um registro com esta chave"));
        }
        case 547 -> {
          return Optional.of(
              new ProblemException(
                  409,
                  "constraint-violation",
                  "Conflito",
                  "A operação viola uma restrição do banco de dados"));
        }
        case 515, 2628, 8152, 8115, 8114, 245, 241, 242, 220, 217, 232, 8134 -> {
          return Optional.of(
              new ProblemException(
                  422,
                  "unprocessable",
                  "Valor não aceito",
                  "Algum valor não é aceito pelo banco do cliente"));
        }
        case 1205, 1222 -> {
          return Optional.of(
              new ProblemException(
                  503,
                  "try-again",
                  "Tente novamente",
                  "Conflito de concorrência no banco do cliente",
                  java.util.List.of(),
                  Map.of("Retry-After", "1")));
        }
        default -> {
          // outros números: seguem para o tratamento genérico
        }
      }
    }
    return Optional.empty();
  }

  /** Números de erro do SQL Server na cadeia de causas, incluindo erros encadeados de lote. */
  private static int[] codes(Throwable error) {
    java.util.List<Integer> found = new java.util.ArrayList<>();
    int guard = 0;
    for (Throwable t = error; t != null && guard++ < 20; t = t.getCause()) {
      if (t instanceof SQLException sql) {
        int inner = 0;
        for (SQLException e = sql; e != null && inner++ < 20; e = e.getNextException()) {
          if (e.getErrorCode() != 0) {
            found.add(e.getErrorCode());
          }
        }
      }
    }
    return found.stream().mapToInt(Integer::intValue).toArray();
  }
}
