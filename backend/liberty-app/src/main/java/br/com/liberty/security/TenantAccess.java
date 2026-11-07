package br.com.liberty.security;

import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Papéis do usuário por tenant, lidos do core a cada decisão (revogar um papel vale na hora). Usado
 * em {@code @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")}.
 * Hierarquia: PUBLISHER inclui INTEGRATOR, que inclui VIEWER. Tenant inexistente e não-membro dão a
 * mesma resposta.
 */
@Component("tenantAccess")
public class TenantAccess {

  private final TenantRepository tenants;
  private final UserRoleRepository roles;

  TenantAccess(TenantRepository tenants, UserRoleRepository roles) {
    this.tenants = tenants;
    this.roles = roles;
  }

  @Transactional(readOnly = true)
  public boolean hasRole(Authentication authentication, String slug, String minimumRole) {
    if (!isUser(authentication)) {
      return false;
    }
    TenantRole minimum;
    try {
      minimum = TenantRole.valueOf(minimumRole);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return tenants
        .findBySlug(slug)
        .filter(tenant -> "ACTIVE".equals(tenant.getStatus()))
        .map(tenant -> roles.findByIdUserSubAndIdTenantId(authentication.getName(), tenant.getId()))
        .map(found -> found.stream().anyMatch(r -> atLeast(r.getId().role(), minimum)))
        .orElse(false);
  }

  /** Tenants e papéis efetivos do usuário (para a interface montar o seletor de tenant). */
  @Transactional(readOnly = true)
  public Map<String, Set<TenantRole>> memberships(String sub) {
    Map<String, Set<TenantRole>> result = new TreeMap<>();
    List<UserRoleEntity> found = roles.findByIdUserSub(sub);
    for (UserRoleEntity role : found) {
      tenants
          .findById(role.getId().tenantId())
          .filter(tenant -> "ACTIVE".equals(tenant.getStatus()))
          .map(TenantEntity::getSlug)
          .ifPresent(
              slug ->
                  result
                      .computeIfAbsent(slug, k -> EnumSet.noneOf(TenantRole.class))
                      .add(role.getId().role()));
    }
    return result;
  }

  private static boolean atLeast(TenantRole actual, TenantRole minimum) {
    return actual.ordinal() >= minimum.ordinal();
  }

  private static boolean isUser(Authentication authentication) {
    return authentication instanceof JwtAuthenticationToken
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(SecurityConstants.ROLE_USER::equals);
  }
}
