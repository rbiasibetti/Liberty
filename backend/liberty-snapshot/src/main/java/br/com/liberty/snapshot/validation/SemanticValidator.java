package br.com.liberty.snapshot.validation;

import br.com.liberty.domain.catalog.InvalidIdentifierException;
import br.com.liberty.domain.catalog.TableRef;
import br.com.liberty.snapshot.TransformSpecs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Regras que o JSON Schema não expressa: coerência entre rota, parâmetros, método e operação, a
 * árvore de mapeamento (profundidade, chaves, colunas repetidas) e as referências cruzadas. Só roda
 * em documentos que já passaram na validação estrutural. A existência de tabelas e colunas é
 * conferida na publicação.
 */
public final class SemanticValidator {

  static final int MAX_DEPTH = 5;
  static final int MAX_NODES = 30;

  private static final Pattern ROUTE_VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
  private static final Map<String, Set<String>> OPERATIONS_BY_METHOD =
      Map.of(
          "GET", Set.of("SELECT"),
          "POST", Set.of("INSERT", "UPSERT"),
          "PUT", Set.of("UPSERT", "UPDATE"),
          "PATCH", Set.of("UPDATE"));

  private final BodySchemaChecker bodySchemaChecker;

  public SemanticValidator(BodySchemaChecker bodySchemaChecker) {
    this.bodySchemaChecker = bodySchemaChecker;
  }

  public List<FieldError> validate(JsonNode doc) {
    List<FieldError> errors = new ArrayList<>();
    Set<String> declared = declaredParams(doc, errors);
    checkRoute(doc, declared, errors);
    String type = doc.path("operation").path("type").asString();
    String method = doc.path("integration").path("method").asString();
    if (!OPERATIONS_BY_METHOD.getOrDefault(method, Set.of()).contains(type)) {
      errors.add(
          new FieldError(
              "/operation/type",
              "method_operation_mismatch",
              "A operação " + type + " não combina com o método " + method));
    }
    JsonNode bodySchema = doc.path("request").path("bodySchema");
    if (!bodySchema.isMissingNode()) {
      errors.addAll(bodySchemaChecker.check(bodySchema, "/request/bodySchema"));
    } else if (!"SELECT".equals(type)) {
      errors.add(
          new FieldError(
              "/request/bodySchema",
              "body_schema_required",
              "Operações de escrita precisam declarar o schema do corpo"));
    }
    if ("SELECT".equals(type)) {
      checkSelect(doc.path("operation").path("select"), doc.path("request").path("params"), errors);
    } else {
      int[] nodes = {0};
      checkNode(
          doc.path("operation").path("write").path("root"),
          "/operation/write/root",
          1,
          true,
          type,
          declared,
          nodes,
          errors);
    }
    checkNamedQueries(doc.path("namedQueries"), errors);
    checkTests(doc.path("tests"), declared, errors);
    return errors;
  }

  /**
   * Parâmetros de sistema da paginação e da ordenação; integrações não podem usá-los como parâmetro
   * próprio.
   */
  public static final Set<String> RESERVED_PARAMS = Set.of("limit", "offset", "sort");

  private static Set<String> declaredParams(JsonNode doc, List<FieldError> errors) {
    Set<String> names = new HashSet<>();
    JsonNode params = doc.path("request").path("params");
    for (int i = 0; i < params.size(); i++) {
      String name = params.get(i).path("name").asString();
      if (RESERVED_PARAMS.contains(name.toLowerCase(Locale.ROOT))) {
        errors.add(
            new FieldError(
                "/request/params/" + i + "/name",
                "reserved_param_name",
                "Nome reservado para paginação e ordenação"));
      }
      if (!names.add(name)) {
        errors.add(
            new FieldError(
                "/request/params/" + i + "/name",
                "duplicate",
                "Parâmetro declarado mais de uma vez"));
      }
    }
    return names;
  }

  private static void checkRoute(JsonNode doc, Set<String> declared, List<FieldError> errors) {
    Set<String> inRoute = new HashSet<>();
    Matcher matcher = ROUTE_VARIABLE.matcher(doc.path("integration").path("route").asString());
    while (matcher.find()) {
      if (!inRoute.add(matcher.group(1))) {
        errors.add(
            new FieldError(
                "/integration/route",
                "duplicate_route_variable",
                "Variável de rota repetida: " + matcher.group(1)));
      }
    }
    JsonNode params = doc.path("request").path("params");
    Set<String> pathParams = new HashSet<>();
    for (int i = 0; i < params.size(); i++) {
      JsonNode param = params.get(i);
      if ("path".equals(param.path("in").asString())) {
        pathParams.add(param.path("name").asString());
        if (!inRoute.contains(param.path("name").asString())) {
          errors.add(
              new FieldError(
                  "/request/params/" + i + "/name",
                  "path_param_not_in_route",
                  "O parâmetro de caminho não aparece na rota"));
        }
        if (param.has("required") && !param.path("required").asBoolean()) {
          errors.add(
              new FieldError(
                  "/request/params/" + i + "/required",
                  "path_param_must_be_required",
                  "Parâmetro de caminho é sempre obrigatório"));
        }
      }
    }
    for (String variable : inRoute) {
      if (!pathParams.contains(variable)) {
        errors.add(
            new FieldError(
                "/integration/route",
                "undeclared_path_param",
                "A variável {" + variable + "} da rota não foi declarada em request.params"));
      }
    }
  }

  private static void checkSelect(JsonNode select, JsonNode params, List<FieldError> errors) {
    checkTable(select.path("table"), "/operation/select/table", errors);
    Set<String> columns = new HashSet<>();
    select.path("columns").forEach(c -> columns.add(c.asString()));
    Set<String> paramNames = new HashSet<>();
    params.forEach(p -> paramNames.add(p.path("name").asString()));
    JsonNode filters = select.path("filters");
    for (int i = 0; i < filters.size(); i++) {
      if (!paramNames.contains(filters.get(i).path("param").asString())) {
        errors.add(
            new FieldError(
                "/operation/select/filters/" + i + "/param",
                "unknown_param",
                "O filtro usa um parâmetro não declarado em request.params"));
      }
    }
    JsonNode sortable = select.path("sortable");
    for (int i = 0; i < sortable.size(); i++) {
      if (!columns.contains(sortable.get(i).asString())) {
        errors.add(
            new FieldError(
                "/operation/select/sortable/" + i,
                "sortable_not_selected",
                "Só colunas retornadas podem ser ordenáveis"));
      }
    }
    JsonNode defaultSort = select.path("defaultSort");
    if (!defaultSort.isMissingNode()) {
      String column = defaultSort.path("column").asString();
      boolean allowed = sortable.isEmpty() ? columns.contains(column) : contains(sortable, column);
      if (!allowed) {
        errors.add(
            new FieldError(
                "/operation/select/defaultSort/column",
                "default_sort_not_allowed",
                "A ordenação padrão deve estar entre as colunas ordenáveis"));
      }
    }
    JsonNode page = select.path("pageSize");
    if (page.has("default")
        && page.has("max")
        && page.path("default").asInt() > page.path("max").asInt()) {
      errors.add(
          new FieldError(
              "/operation/select/pageSize/default",
              "page_default_above_max",
              "O tamanho padrão da página excede o máximo"));
    }
  }

  private static boolean contains(JsonNode array, String value) {
    for (JsonNode item : array) {
      if (value.equals(item.asString())) {
        return true;
      }
    }
    return false;
  }

  private static void checkTable(JsonNode table, String path, List<FieldError> errors) {
    try {
      TableRef.parse(table.asString());
    } catch (InvalidIdentifierException e) {
      errors.add(new FieldError(path, "invalid_table", "Referência de tabela inválida"));
    }
  }

  private static void checkNode(
      JsonNode node,
      String path,
      int depth,
      boolean root,
      String type,
      Set<String> declared,
      int[] counter,
      List<FieldError> errors) {
    if (++counter[0] == MAX_NODES + 1) {
      errors.add(
          new FieldError(
              path, "too_many_nodes", "A árvore de mapeamento tem mais de " + MAX_NODES + " nós"));
    }
    if (depth > MAX_DEPTH) {
      errors.add(
          new FieldError(
              path, "too_deep", "A árvore de mapeamento passa de " + MAX_DEPTH + " níveis"));
      return;
    }
    checkTable(node.path("table"), path + "/table", errors);
    if (root && node.has("arrayPath")) {
      errors.add(
          new FieldError(
              path + "/arrayPath",
              "root_has_array_path",
              "O nó raiz mapeia o corpo inteiro e não tem arrayPath"));
    }
    if (!root && !node.has("arrayPath")) {
      errors.add(
          new FieldError(
              path + "/arrayPath", "array_path_required", "Nós filhos precisam de arrayPath"));
    }
    if (!root && !node.has("foreignKey")) {
      errors.add(
          new FieldError(
              path + "/foreignKey",
              "foreign_key_required",
              "Nós filhos precisam da coluna de chave estrangeira para o pai"));
    }
    if (root && node.has("foreignKey")) {
      errors.add(
          new FieldError(path + "/foreignKey", "root_has_foreign_key", "O nó raiz não tem pai"));
    }
    Set<String> targets = checkFields(node.path("fields"), path + "/fields", declared, errors);
    if (node.has("foreignKey")
        && targets.contains(node.path("foreignKey").asString().toLowerCase(Locale.ROOT))) {
      errors.add(
          new FieldError(
              path + "/foreignKey",
              "foreign_key_is_mapped",
              "A chave estrangeira é preenchida pelo motor e não pode ser mapeada"));
    }
    checkKeys(node, path, root, type, targets, errors);
    JsonNode children = node.path("children");
    if (!children.isEmpty()) {
      if (!"INSERT".equals(type)) {
        errors.add(
            new FieldError(
                path + "/children",
                "children_only_for_insert",
                "Nós filhos só são suportados em INSERT"));
      }
      if (!node.has("primaryKey")) {
        errors.add(
            new FieldError(
                path + "/primaryKey",
                "primary_key_required_for_children",
                "Nós com filhos precisam informar a chave primária"));
      }
    }
    for (int i = 0; i < children.size(); i++) {
      checkNode(
          children.get(i),
          path + "/children/" + i,
          depth + 1,
          false,
          type,
          declared,
          counter,
          errors);
    }
  }

  private static Set<String> checkFields(
      JsonNode fields, String path, Set<String> declared, List<FieldError> errors) {
    Set<String> targets = new HashSet<>();
    for (int i = 0; i < fields.size(); i++) {
      JsonNode field = fields.get(i);
      String here = path + "/" + i;
      if (!targets.add(field.path("column").asString().toLowerCase(Locale.ROOT))) {
        errors.add(
            new FieldError(
                here + "/column",
                "duplicate_column",
                "Coluna mapeada mais de uma vez no mesmo nó"));
      }
      if (field.has("param") && !declared.contains(field.path("param").asString())) {
        errors.add(
            new FieldError(
                here + "/param", "unknown_param", "Parâmetro não declarado em request.params"));
      }
      JsonNode transforms = field.path("transforms");
      for (int t = 0; t < transforms.size(); t++) {
        JsonNode transform = transforms.get(t);
        String transformPath = here + "/transforms/" + t + "/args";
        TransformSpecs.check(transform.path("fn").asString(), transform.get("args"))
            .ifPresent(
                message -> errors.add(new FieldError(transformPath, "invalid_transform", message)));
      }
    }
    return targets;
  }

  private static void checkKeys(
      JsonNode node,
      String path,
      boolean root,
      String type,
      Set<String> targets,
      List<FieldError> errors) {
    boolean keyed = "UPSERT".equals(type) || "UPDATE".equals(type);
    if (root && keyed && !node.has("keyColumns")) {
      errors.add(
          new FieldError(
              path + "/keyColumns",
              "key_columns_required",
              type + " exige a chave completa em keyColumns"));
    }
    if (node.has("keyColumns") && !(root && keyed)) {
      errors.add(
          new FieldError(
              path + "/keyColumns",
              "key_columns_not_allowed",
              "keyColumns só vale no nó raiz de UPSERT e UPDATE"));
    }
    JsonNode keys = node.path("keyColumns");
    for (int i = 0; i < keys.size(); i++) {
      if (!targets.contains(keys.get(i).asString().toLowerCase(Locale.ROOT))) {
        errors.add(
            new FieldError(
                path + "/keyColumns/" + i,
                "key_column_not_mapped",
                "A coluna da chave precisa ser mapeada em fields"));
      }
    }
  }

  private static void checkNamedQueries(JsonNode queries, List<FieldError> errors) {
    Set<String> names = new HashSet<>();
    for (int i = 0; i < queries.size(); i++) {
      if (!names.add(queries.get(i).path("name").asString())) {
        errors.add(
            new FieldError(
                "/namedQueries/" + i + "/name", "duplicate", "Nome de consulta repetido"));
      }
    }
  }

  private static void checkTests(JsonNode tests, Set<String> declared, List<FieldError> errors) {
    Set<String> names = new HashSet<>();
    for (int i = 0; i < tests.size(); i++) {
      JsonNode test = tests.get(i);
      if (!names.add(test.path("name").asString())) {
        errors.add(new FieldError("/tests/" + i + "/name", "duplicate", "Nome de teste repetido"));
      }
      String testPath = "/tests/" + i + "/request/params/";
      test.path("request")
          .path("params")
          .propertyNames()
          .forEach(
              name -> {
                if (!declared.contains(name)) {
                  errors.add(
                      new FieldError(
                          testPath + SchemaValidator.escape(name),
                          "unknown_param",
                          "O teste usa um parâmetro não declarado"));
                }
              });
    }
  }
}
