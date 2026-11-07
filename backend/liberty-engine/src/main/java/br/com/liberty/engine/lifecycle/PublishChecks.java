package br.com.liberty.engine.lifecycle;

import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.snapshot.model.Node;
import br.com.liberty.snapshot.model.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conferências de publicação que não dependem do banco do cliente: o que o esquema do snapshot não
 * consegue dizer sozinho. As que dependem do banco (tabelas e colunas existentes) rodam na
 * publicação, contra o catálogo do ambiente.
 */
final class PublishChecks {

  private static final Pattern READ_ONLY =
      Pattern.compile("^\\s*(select|with)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private PublishChecks() {}

  static List<Violation> check(Snapshot snapshot, String key, String method, String route) {
    List<Violation> violations = new ArrayList<>();
    var integration = snapshot.integration();
    if (!integration.key().equals(key)
        || !integration.method().equals(method)
        || !integration.route().equals(route)) {
      violations.add(
          new Violation(
              "/integration",
              "identity_mismatch",
              "Chave, método e rota do snapshot devem ser os do cadastro da integração"));
    }
    String type = snapshot.operation().type();
    if ("SELECT".equals(type)
        && snapshot.scripts().beforeExecute() != null
        && !snapshot.scripts().beforeExecute().isBlank()) {
      violations.add(
          new Violation(
              "/scripts/beforeExecute",
              "before_execute_on_select",
              "beforeExecute só vale em integrações de escrita"));
    }
    for (int i = 0; i < snapshot.namedQueries().size(); i++) {
      String sql = snapshot.namedQueries().get(i).sql();
      if (sql.contains(";") || !READ_ONLY.matcher(sql).matches()) {
        violations.add(
            new Violation(
                "/namedQueries/" + i + "/sql",
                "not_a_simple_select",
                "A consulta deve ser um único SELECT (ou WITH ... SELECT), sem ponto e vírgula"));
      }
    }
    return violations;
  }

  /** Tabelas citadas pelo snapshot (leitura ou árvore de escrita). */
  static List<String> tables(Snapshot snapshot) {
    List<String> tables = new ArrayList<>();
    if (snapshot.operation().select() != null) {
      tables.add(snapshot.operation().select().table());
    }
    if (snapshot.operation().write() != null) {
      collect(snapshot.operation().write().root(), tables);
    }
    return tables;
  }

  private static void collect(Node node, List<String> tables) {
    tables.add(node.table());
    node.children().forEach(child -> collect(child, tables));
  }
}
