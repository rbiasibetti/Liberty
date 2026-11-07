package br.com.liberty.snapshot.validation;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * O schema do corpo é escrito pelo integrador e executado a cada requisição, então é tratado como
 * entrada hostil: precisa ser um JSON Schema 2020-12 válido, ter tamanho e profundidade limitados,
 * referenciar só a si mesmo ($ref local, nunca arquivo nem URL, que seria leitura arbitrária ou
 * SSRF) e não conter expressões regulares com repetição aninhada (ReDoS).
 */
public final class BodySchemaChecker {

  private static final int MAX_DEPTH = 32;
  private static final int MAX_NODES = 5_000;
  private static final int MAX_PATTERN_LENGTH = 200;
  private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

  /**
   * Grupo que contém um quantificador e é seguido de outro quantificador: (a+)+, (.*)*, (a|b+){2,}.
   */
  private static final Pattern NESTED_QUANTIFIER =
      Pattern.compile("\\((?:[^()\\\\]|\\\\.)*[+*}](?:[^()\\\\]|\\\\.)*\\)[+*{]");

  private final Schema metaSchema =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(SchemaLocation.of(DRAFT_2020_12));

  /** Erros do schema do corpo, com caminhos a partir de {@code base}. */
  public List<FieldError> check(JsonNode bodySchema, String base) {
    List<FieldError> errors = new ArrayList<>();
    if (bodySchema == null || bodySchema.isBoolean()) {
      return errors;
    }
    int[] counter = {0};
    walk(bodySchema, base, 0, counter, errors);
    if (!errors.isEmpty()) {
      return errors; // estrutura inaceitável: nem tenta a metavalidação
    }
    for (Error error : metaSchema.validate(bodySchema)) {
      errors.add(
          new FieldError(
              base + SchemaValidator.pointer(error.getInstanceLocation().toString()),
              "invalid_json_schema",
              "Não é um JSON Schema 2020-12 válido neste ponto"));
    }
    return errors;
  }

  private static void walk(
      JsonNode node, String path, int depth, int[] counter, List<FieldError> errors) {
    if (++counter[0] > MAX_NODES) {
      if (counter[0] == MAX_NODES + 1) {
        errors.add(new FieldError(path, "too_large", "Schema do corpo grande demais"));
      }
      return;
    }
    if (depth > MAX_DEPTH) {
      errors.add(new FieldError(path, "too_deep", "Schema do corpo aninhado demais"));
      return;
    }
    if (node.isObject()) {
      node.properties().forEach(entry -> inspect(entry.getKey(), entry.getValue(), path, errors));
      node.properties()
          .forEach(
              entry ->
                  walk(
                      entry.getValue(),
                      path + "/" + SchemaValidator.escape(entry.getKey()),
                      depth + 1,
                      counter,
                      errors));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        walk(node.get(i), path + "/" + i, depth + 1, counter, errors);
      }
    }
  }

  private static void inspect(String key, JsonNode value, String path, List<FieldError> errors) {
    String here = path + "/" + SchemaValidator.escape(key);
    switch (key) {
      case "$ref", "$dynamicRef", "$recursiveRef" -> {
        if (!value.isString() || !value.asString().startsWith("#")) {
          errors.add(
              new FieldError(
                  here,
                  "remote_ref",
                  "Só referências locais ($ref iniciado por #) são permitidas"));
        }
      }
      case "$schema" -> {
        if (!value.isString() || !DRAFT_2020_12.equals(value.asString())) {
          errors.add(
              new FieldError(here, "unsupported_dialect", "Só JSON Schema 2020-12 é suportado"));
        }
      }
      case "$id", "$anchor", "$vocabulary", "$comment" -> {
        if ("$id".equals(key) || "$vocabulary".equals(key)) {
          errors.add(new FieldError(here, "forbidden_keyword", "Palavra-chave não permitida"));
        }
      }
      case "pattern" -> checkPattern(value, here, errors);
      case "patternProperties" -> {
        if (value.isObject()) {
          value
              .propertyNames()
              .forEach(
                  pattern ->
                      checkPattern(
                          tools.jackson.databind.node.StringNode.valueOf(pattern), here, errors));
        }
      }
      default -> {
        // demais palavras-chave são validadas pela metavalidação
      }
    }
  }

  private static void checkPattern(JsonNode value, String path, List<FieldError> errors) {
    if (!value.isString()) {
      return;
    }
    String pattern = value.asString();
    if (pattern.length() > MAX_PATTERN_LENGTH) {
      errors.add(
          new FieldError(
              path,
              "pattern_too_long",
              "Expressão regular com mais de " + MAX_PATTERN_LENGTH + " caracteres"));
    } else if (NESTED_QUANTIFIER.matcher(pattern).find()) {
      errors.add(
          new FieldError(
              path,
              "unsafe_pattern",
              "Expressão regular com repetição aninhada (risco de travamento)"));
    } else {
      try {
        Pattern.compile(pattern);
      } catch (java.util.regex.PatternSyntaxException e) {
        errors.add(new FieldError(path, "invalid_pattern", "Expressão regular inválida"));
      }
    }
  }
}
