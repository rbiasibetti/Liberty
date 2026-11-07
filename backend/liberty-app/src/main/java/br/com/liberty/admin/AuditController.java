package br.com.liberty.admin;

import br.com.liberty.engine.problem.ProblemException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consulta da auditoria de execuções do tenant (papel INTEGRATOR ou superior). */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/audit")
class AuditController {

  private final AuditQueryService service;

  AuditController(AuditQueryService service) {
    this.service = service;
  }

  @GetMapping("/executions")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  ResponseEntity<AuditQueryService.Page> executions(
      @PathVariable String slug,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String integration,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String requestId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var result =
        service.search(
            slug,
            instant("from", from),
            instant("to", to),
            integration,
            status,
            requestId,
            page,
            size);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }

  private static Instant instant(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw ProblemException.validation(
          List.of(
              new ProblemException.Violation(
                  name, "invalid_value", "Use data e hora ISO 8601 em UTC")));
    }
  }
}
