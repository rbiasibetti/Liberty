package br.com.liberty.admin;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenants da plataforma: só o administrador da plataforma cria, lista, altera e confere o
 * onboarding.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantsController {

  private final PlatformTenantService service;

  PlatformTenantsController(PlatformTenantService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  List<PlatformTenantService.TenantView> list() {
    return service.list();
  }

  @PostMapping
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ResponseEntity<PlatformTenantService.TenantView> create(
      @RequestBody PlatformTenantService.CreateRequest body, Authentication admin) {
    return ResponseEntity.status(201).body(service.create(body, admin.getName()));
  }

  @PatchMapping("/{slug}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  PlatformTenantService.TenantView update(
      @PathVariable String slug,
      @RequestBody PlatformTenantService.UpdateRequest body,
      Authentication admin) {
    return service.update(slug, body, admin.getName());
  }

  @GetMapping("/{slug}/onboarding")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  PlatformTenantService.Onboarding onboarding(@PathVariable String slug) {
    return service.onboarding(slug);
  }
}
