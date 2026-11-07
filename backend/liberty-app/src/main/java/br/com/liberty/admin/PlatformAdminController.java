package br.com.liberty.admin;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.infrastructure.tenancy.ConnectionTester;
import java.util.List;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administração do tenant, só para o administrador da plataforma (papel do provedor OIDC, nunca de
 * um claim do usuário comum): conexões, clientes OAuth2 e papéis. Respostas nunca levam senhas, e o
 * segredo de um cliente novo só aparece na resposta da criação.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants/{slug}")
class PlatformAdminController {

  private final TenantAdminService service;

  PlatformAdminController(TenantAdminService service) {
    this.service = service;
  }

  @GetMapping("/connections")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  List<TenantAdminService.ConnectionView> connections(@PathVariable String slug) {
    return service.connections(slug);
  }

  @PutMapping("/connections/{env}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  TenantAdminService.ConnectionView saveConnection(
      @PathVariable String slug,
      @PathVariable String env,
      @RequestBody TenantAdminService.ConnectionRequest body,
      Authentication admin) {
    return service.saveConnection(slug, environment(env), body, admin.getName());
  }

  @PostMapping("/connections/{env}/test")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ConnectionTester.Result testConnection(@PathVariable String slug, @PathVariable String env) {
    return service.testConnection(slug, environment(env));
  }

  @GetMapping("/clients")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  List<TenantAdminService.ClientView> clients(@PathVariable String slug) {
    return service.clients(slug);
  }

  @PostMapping("/clients")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ResponseEntity<TenantAdminService.CreatedClient> createClient(
      @PathVariable String slug,
      @RequestBody TenantAdminService.ClientRequest body,
      Authentication admin) {
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(service.createClient(slug, body, admin.getName()));
  }

  @DeleteMapping("/clients/{clientId}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ResponseEntity<Void> revokeClient(
      @PathVariable String slug, @PathVariable String clientId, Authentication admin) {
    service.revokeClient(slug, clientId, admin.getName());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  List<TenantAdminService.UserView> users(@PathVariable String slug) {
    return service.users(slug);
  }

  @PutMapping("/users/{sub:.+}/roles/{role}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ResponseEntity<Void> grant(
      @PathVariable String slug,
      @PathVariable String sub,
      @PathVariable String role,
      Authentication admin) {
    service.grant(slug, sub, role, admin.getName());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/users/{sub:.+}/roles/{role}")
  @PreAuthorize("hasRole('PLATFORM_ADMIN')")
  ResponseEntity<Void> revoke(
      @PathVariable String slug,
      @PathVariable String sub,
      @PathVariable String role,
      Authentication admin) {
    service.revoke(slug, sub, role, admin.getName());
    return ResponseEntity.noContent().build();
  }

  private static EnvironmentKind environment(String env) {
    try {
      return EnvironmentKind.valueOf(env.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ProblemException(
          400, "validation", "Requisição inválida", "Ambiente deve ser HML ou PRD");
    }
  }
}
