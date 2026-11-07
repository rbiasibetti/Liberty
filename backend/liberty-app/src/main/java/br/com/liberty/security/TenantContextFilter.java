package br.com.liberty.security;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nas rotas de execução ({@code /{slug}/api/...}) o tenant vem do token, nunca do cliente: o slug
 * da URL só é conferido e, se diferir do tenant do token, a resposta é 404 (sem revelar que o
 * tenant existe). O contexto é limpo ao fim de toda requisição.
 */
final class TenantContextFilter extends OncePerRequestFilter {

  private static final Pattern EXECUTION_PATH = Pattern.compile("^/([^/]+)/api(/.*)?$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      Matcher path = EXECUTION_PATH.matcher(request.getRequestURI());
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (path.matches() && authentication instanceof JwtAuthenticationToken jwt && isClient(jwt)) {
        TenantScope scope = scopeOf(jwt);
        if (scope == null || !scope.tenant().value().equals(path.group(1))) {
          response.sendError(HttpServletResponse.SC_NOT_FOUND);
          return;
        }
        TenantContext.set(scope);
      }
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private static boolean isClient(JwtAuthenticationToken jwt) {
    return jwt.getAuthorities().stream()
        .anyMatch(a -> SecurityConstants.ROLE_CLIENT.equals(a.getAuthority()));
  }

  private static TenantScope scopeOf(JwtAuthenticationToken jwt) {
    try {
      return new TenantScope(
          new TenantId(jwt.getToken().getClaimAsString(SecurityConstants.CLAIM_TENANT)),
          EnvironmentKind.valueOf(jwt.getToken().getClaimAsString(SecurityConstants.CLAIM_ENV)));
    } catch (RuntimeException e) {
      return null;
    }
  }
}
