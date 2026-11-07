package br.com.liberty.security;

import br.com.liberty.domain.tenancy.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints só de teste: um por regra de acesso, para a matriz de acesso cobrir cada combinação.
 */
@RestController
class AccessProbeController {

  @GetMapping("/{slug}/api/echo")
  @PreAuthorize("hasRole('CLIENT')")
  String echo(@PathVariable String slug) {
    return TenantContext.require().toString();
  }

  @GetMapping("/api/v1/tenants/{slug}/probe/viewer")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  String viewer(@PathVariable String slug) {
    return "ok";
  }

  @GetMapping("/api/v1/tenants/{slug}/probe/integrator")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  String integrator(@PathVariable String slug) {
    return "ok";
  }

  @GetMapping("/api/v1/tenants/{slug}/probe/publisher")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  String publisher(@PathVariable String slug) {
    return "ok";
  }

  @GetMapping("/api/v1/platform/probe")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  String platform() {
    return "ok";
  }
}
