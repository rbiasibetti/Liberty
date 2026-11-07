package br.com.liberty.snapshot.validation;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Valida a estrutura do snapshot contra o JSON Schema publicado e devolve um erro por campo. */
public final class SchemaValidator {

  private static final Map<String, String> MESSAGES =
      Map.ofEntries(
          Map.entry("required", "Campo obrigatório"),
          Map.entry("additionalProperties", "Campo desconhecido"),
          Map.entry("type", "Tipo de valor inválido"),
          Map.entry("enum", "Valor não permitido"),
          Map.entry("const", "Valor não suportado nesta versão do snapshot"),
          Map.entry("pattern", "Formato inválido"),
          Map.entry("minLength", "Texto curto demais"),
          Map.entry("maxLength", "Texto longo demais"),
          Map.entry("minItems", "Itens de menos"),
          Map.entry("maxItems", "Itens demais"),
          Map.entry("uniqueItems", "Itens repetidos"),
          Map.entry("minimum", "Valor abaixo do mínimo"),
          Map.entry("maximum", "Valor acima do máximo"),
          Map.entry("oneOf", "Informe exatamente um entre source, param e constant"),
          Map.entry("not", "Combinação de campos não permitida para este tipo de operação"));

  private final Schema schema;

  public SchemaValidator(String resource) {
    try (InputStream stream = SchemaValidator.class.getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("JSON Schema não encontrado: " + resource);
      }
      this.schema =
          SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<FieldError> validate(JsonNode document) {
    List<FieldError> errors = new ArrayList<>();
    for (Error error : schema.validate(document)) {
      String keyword = error.getKeyword();
      String path = pointer(error.getInstanceLocation().toString());
      if (("required".equals(keyword) || "additionalProperties".equals(keyword))
          && error.getProperty() != null) {
        path = path + "/" + escape(error.getProperty());
      }
      errors.add(new FieldError(path, keyword, MESSAGES.getOrDefault(keyword, "Valor inválido")));
    }
    return errors;
  }

  /**
   * O validador devolve o caminho em notação "$.a[0].b" ou já como pointer; normaliza para JSON
   * Pointer.
   */
  static String pointer(String location) {
    if (location.isEmpty() || location.equals("$")) {
      return "";
    }
    if (location.startsWith("/")) {
      return location;
    }
    StringBuilder out = new StringBuilder();
    String rest = location.startsWith("$") ? location.substring(1) : location;
    int i = 0;
    while (i < rest.length()) {
      char c = rest.charAt(i);
      if (c == '.') {
        int j = i + 1;
        while (j < rest.length() && rest.charAt(j) != '.' && rest.charAt(j) != '[') {
          j++;
        }
        out.append('/').append(escape(rest.substring(i + 1, j)));
        i = j;
      } else if (c == '[') {
        int j = rest.indexOf(']', i);
        String inner = rest.substring(i + 1, j);
        out.append('/')
            .append(inner.startsWith("'") ? escape(inner.substring(1, inner.length() - 1)) : inner);
        i = j + 1;
      } else {
        i++;
      }
    }
    return out.toString();
  }

  static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }
}
