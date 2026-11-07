package br.com.liberty.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Respostas 401 e 403 como problem+json (RFC 9457), sem detalhes que ajudem um atacante. */
@Component
class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final BearerTokenAuthenticationEntryPoint bearer =
      new BearerTokenAuthenticationEntryPoint();

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    bearer.commence(request, response, exception); // define 401 e WWW-Authenticate: Bearer
    write(response, HttpStatus.UNAUTHORIZED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    write(response, HttpStatus.FORBIDDEN);
  }

  private static void write(HttpServletResponse response, HttpStatus status) throws IOException {
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + status.getReasonPhrase()
                + "\",\"status\":"
                + status.value()
                + "}");
  }
}
