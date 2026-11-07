package br.com.liberty;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.liberty.infrastructure.testsupport.CoreTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LibertyApplicationTests {

  @DynamicPropertySource
  static void coreDatabase(DynamicPropertyRegistry registry) {
    var db = CoreTestDatabase.create();
    registry.add("spring.datasource.url", db::url);
    registry.add("spring.datasource.username", db::user);
    registry.add("spring.datasource.password", db::password);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/core");
    registry.add("spring.flyway.table", () -> "int_flyway_schema_history");
    registry.add("liberty.vault.uri", () -> "http://127.0.0.1:8200");
    registry.add("liberty.vault.token", () -> "teste");
  }

  @Autowired private MockMvc mvc;

  @Test
  void healthEndpointIsUpAndHidesDetails() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  void readinessDependsOnTheCoreDatabase() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void livenessDoesNotDependOnTheDatabase() throws Exception {
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void sensitiveActuatorEndpointsAreNotReachable() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }
}
