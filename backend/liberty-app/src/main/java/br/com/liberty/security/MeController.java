package br.com.liberty.security;

import br.com.liberty.domain.tenancy.TenantRole;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quem sou eu: a interface chama no login para montar o seletor de tenant. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

  /** Resposta: usuário, se é administrador da plataforma e os tenants com papéis efetivos. */
  record Me(String sub, boolean platformAdmin, Map<String, Set<TenantRole>> tenants) {}

  private final TenantAccess access;

  MeController(TenantAccess access) {
    this.access = access;
  }

  @GetMapping
  @PreAuthorize("hasRole('USER')")
  ResponseEntity<Me> me(Authentication authentication) {
    boolean admin =
        authentication.getAuthorities().stream()
            .anyMatch(a -> SecurityConstants.ROLE_PLATFORM_ADMIN.equals(a.getAuthority()));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new Me(authentication.getName(), admin, access.memberships(authentication.getName())));
  }
}
