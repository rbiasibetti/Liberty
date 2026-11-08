package br.com.liberty.engine.write;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.engine.read.ValueBinder;
import br.com.liberty.snapshot.model.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Transforma o corpo JSON e os parâmetros numa árvore de linhas tipadas, sem tocar no banco. Todos
 * os defeitos de entrada aparecem aqui, juntos, como violações por campo; só uma entrada
 * inteiramente válida chega ao executor.
 */
final class Materializer {

  private static final int MAX_VIOLATIONS = 50;
  private static final Object ABSENT = new Object();
  private static final int MAX_SCALE = 100;

  private final FieldTransformer transformer;
  private final int maxRows;

  Materializer(FieldTransformer transformer, int maxRows) {
    this.transformer = transformer;
    this.maxRows = maxRows;
  }

  private final class Run {
    final Map<String, String> params;
    final String operationType;
    final List<Violation> violations = new ArrayList<>();
    int rows;

    Run(Map<String, String> params, String operationType) {
      this.params = params;
      this.operationType = operationType;
    }

    void violation(String path, String code, String message) {
      if (violations.size() < MAX_VIOLATIONS) {
        violations.add(new Violation(path, code, message));
      }
    }
  }

  RowData build(WritePlan root, JsonNode body, Map<String, String> params, String operationType) {
    Run run = new Run(params, operationType);
    if (!body.isObject()) {
      throw ProblemException.validation(
          List.of(new Violation("/", "invalid_type", "O corpo deve ser um objeto JSON")));
    }
    RowData data = row(root, body, "", run, true);
    if (!run.violations.isEmpty()) {
      throw ProblemException.validation(run.violations);
    }
    return data;
  }

  private RowData row(WritePlan plan, JsonNode item, String pointer, Run run, boolean root) {
    if (++run.rows > maxRows) {
      throw new ProblemException(
          413,
          "too-many-rows",
          "Escrita grande demais",
          "A requisição geraria mais de " + maxRows + " linhas");
    }
    boolean inserts = "INSERT".equals(run.operationType) || "UPSERT".equals(run.operationType);
    List<RowData.Cell> cells = new ArrayList<>();
    for (WritePlan.FieldPlan field : plan.fields()) {
      Object value = resolve(field, item, pointer, run);
      boolean absent = value == ABSENT;
      if (!absent) {
        cells.add(new RowData.Cell(field.column(), value));
      }
      boolean required = field.column().required() && inserts;
      if (required && (absent || value == null) && run.violations.size() < MAX_VIOLATIONS) {
        run.violation(field.violationPath(pointer), "required", "Valor obrigatório");
      }
    }
    if (root) {
      checkKeys(plan, cells, run);
    }
    List<List<RowData>> children = new ArrayList<>();
    for (WritePlan child : plan.children()) {
      children.add(childRows(child, item, pointer, run));
    }
    return new RowData(plan, cells, children);
  }

  private static void checkKeys(WritePlan plan, List<RowData.Cell> cells, Run run) {
    for (var key : plan.keyColumns()) {
      boolean present =
          cells.stream().anyMatch(c -> c.column().name().equals(key.name()) && c.value() != null);
      if (!present) {
        run.violation(key.name(), "required", "A chave é obrigatória");
      }
    }
  }

  private List<RowData> childRows(WritePlan child, JsonNode parent, String pointer, Run run) {
    JsonNode array = parent.at(child.arrayPointer());
    if (array.isMissingNode() || array.isNull()) {
      return List.of();
    }
    String base = pointer + child.arrayPath();
    if (!array.isArray()) {
      run.violation(base, "invalid_type", "Esperada uma lista");
      return List.of();
    }
    List<RowData> rows = new ArrayList<>();
    for (int i = 0; i < array.size(); i++) {
      JsonNode element = array.get(i);
      String itemPointer = base + "/" + i;
      if (!element.isObject()) {
        run.violation(itemPointer, "invalid_type", "Esperado um objeto");
        continue;
      }
      rows.add(row(child, element, itemPointer, run, false));
    }
    return rows;
  }

  private Object resolve(WritePlan.FieldPlan plan, JsonNode item, String pointer, Run run) {
    Field field = plan.field();
    String path = plan.violationPath(pointer);
    String raw;
    boolean absent = false;
    if (field.source() != null) {
      JsonNode node = item.at(plan.pointer());
      absent = node.isMissingNode();
      raw = absent ? null : text(node, path, run);
    } else if (field.param() != null) {
      raw = run.params.get(field.param());
      absent = raw == null;
    } else {
      raw = text(field.constant(), path, run);
    }
    if (!field.transforms().isEmpty()) {
      try {
        String transformed = transformer.apply(raw, field.transforms());
        absent = absent && transformed == null;
        raw = transformed;
      } catch (FieldTransformer.TransformException e) {
        run.violation(path, "invalid_value", "Valor não pôde ser transformado");
        return ABSENT;
      }
    }
    if (absent) {
      return ABSENT;
    }
    if (raw == null) {
      return null;
    }
    try {
      return ValueBinder.bindForWrite(plan.column(), raw);
    } catch (ValueBinder.InvalidValueException e) {
      run.violation(path, "invalid_value", e.getMessage());
      return ABSENT;
    }
  }

  /** Texto de um valor escalar; {@code null} para JSON null; contêineres são recusados. */
  private static String text(JsonNode node, String path, Run run) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isNumber()) {
      var number = node.decimalValue();
      if (Math.abs(number.scale()) > MAX_SCALE) {
        run.violation(path, "invalid_value", "Número fora do intervalo aceito");
        return null;
      }
      return number.toPlainString();
    }
    if (node.isBoolean()) {
      return node.asBoolean() ? "true" : "false";
    }
    run.violation(path, "invalid_type", "Esperado um valor simples");
    return null;
  }
}
