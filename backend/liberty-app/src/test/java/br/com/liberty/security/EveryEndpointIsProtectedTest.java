package br.com.liberty.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Trava de segurança para o futuro: todo endpoint de aplicação precisa declarar @PreAuthorize. Um
 * endpoint novo sem regra explícita quebra este teste, em vez de depender só da regra "negar o
 * resto".
 */
@SpringBootTest
@ActiveProfiles("test")
class EveryEndpointIsProtectedTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    var db = CoreTestDatabase.create();
    registry.add("spring.datasource.url", db::url);
    registry.add("spring.datasource.username", db::user);
    registry.add("spring.datasource.password", db::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
  }

  @Autowired Map<String, RequestMappingHandlerMapping> mappings;

  @Test
  void everyApplicationHandlerDeclaresItsAccessRule() {
    List<String> unprotected = new ArrayList<>();
    int checked = 0;
    var handlers =
        new java.util.LinkedHashMap<
            org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod>();
    mappings.values().forEach(m -> handlers.putAll(m.getHandlerMethods()));
    for (var entry : handlers.entrySet()) {
      HandlerMethod handler = entry.getValue();
      if (handler.getBeanType().getName().startsWith("org.springframework.")) {
        continue; // /error e semelhantes da infraestrutura
      }
      checked++;
      boolean annotated =
          AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
              || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
      if (!annotated) {
        unprotected.add(entry.getKey() + " -> " + handler.getMethod().getName());
      }
    }

    assertThat(checked).as("o teste não pode ficar vazio").isGreaterThanOrEqualTo(6);
    assertThat(unprotected).isEmpty();
  }
}
