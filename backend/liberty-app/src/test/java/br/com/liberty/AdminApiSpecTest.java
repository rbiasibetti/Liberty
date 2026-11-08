package br.com.liberty;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;

/**
 * A especificação OpenAPI da API de administração (de onde o frontend gera o cliente) tem de listar
 * exatamente os endpoints dos controladores: acrescentar, remover ou renomear um endpoint sem
 * atualizar {@code docs/openapi/admin-api.yaml} quebra este teste.
 */
class AdminApiSpecTest {

  private static final Path SPEC = Path.of("..", "..", "docs", "openapi", "admin-api.yaml");

  @SuppressWarnings("unchecked")
  private static Set<String> specEndpoints() throws IOException {
    try (InputStream in = Files.newInputStream(SPEC)) {
      Map<String, Object> doc = new Yaml().load(in);
      Set<String> endpoints = new TreeSet<>();
      ((Map<String, Map<String, Object>>) doc.get("paths"))
          .forEach(
              (path, item) ->
                  item.keySet().stream()
                      .filter(k -> Set.of("get", "post", "put", "patch", "delete").contains(k))
                      .forEach(k -> endpoints.add(k.toUpperCase() + " " + path)));
      return endpoints;
    }
  }

  private static Set<String> codeEndpoints() throws ClassNotFoundException {
    Set<String> endpoints = new TreeSet<>();
    for (String name :
        new String[] {
          "br.com.liberty.web.LifecycleController",
          "br.com.liberty.security.MeController",
          "br.com.liberty.admin.PlatformAdminController",
          "br.com.liberty.admin.AuditController",
          "br.com.liberty.web.QueueController",
          "br.com.liberty.web.ExportController",
          "br.com.liberty.web.ExportDownloadController"
        }) {
      Class<?> controller = Class.forName(name);
      RequestMapping base =
          AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
      String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
      for (Method method : controller.getDeclaredMethods()) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
          continue;
        }
        String path =
            (prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]))
                .replaceAll("\\{(\\w+):[^}]+\\}", "{$1}");
        for (RequestMethod verb : mapping.method()) {
          endpoints.add(verb.name() + " " + path);
        }
      }
    }
    return endpoints;
  }

  @Test
  void theSpecListsExactlyTheEndpointsOfTheAdminControllers() throws Exception {
    assertThat(specEndpoints()).isEqualTo(codeEndpoints());
  }
}
