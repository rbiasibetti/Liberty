package br.com.liberty.web;

import br.com.liberty.engine.exports.ExportService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exportações do tenant. Exportar entrega dados do banco do cliente, então exige INTEGRATOR (quem
 * já pode ler esses dados no sandbox); VIEWER não exporta. O arquivo sai por uma URL assinada e de
 * vida curta, no {@link ExportDownloadController}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}/exports")
class ExportController {

  private final ExportService exports;

  ExportController(ExportService exports) {
    this.exports = exports;
  }

  @PostMapping
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  ResponseEntity<ExportService.View> request(
      @PathVariable String slug, @RequestBody ExportService.Request body, Authentication user) {
    return ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(exports.request(slug, user.getName(), body));
  }

  @GetMapping
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  List<ExportService.View> list(@PathVariable String slug) {
    return exports.list(slug);
  }

  @GetMapping("/{id}")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  ExportService.View get(@PathVariable String slug, @PathVariable long id) {
    return exports.get(slug, id);
  }

  @PostMapping("/{id}/link")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  ResponseEntity<ExportService.Link> link(
      @PathVariable String slug, @PathVariable long id, Authentication user) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(exports.link(slug, id, user.getName()));
  }
}
