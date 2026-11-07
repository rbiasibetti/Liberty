package br.com.liberty.web;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.read.ReadEngine;
import br.com.liberty.engine.read.ReadResult;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.engine.route.RouteMatch;
import br.com.liberty.engine.route.RouteTable;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

/**
 * Porta de entrada das integrações publicadas: {@code /{tenant}/api/...}. O tenant e o ambiente vêm
 * do token (o filtro de segurança já os pôs no contexto e recusou slug alheio); aqui a rota é
 * resolvida entre as publicações ativas do tenant, o escopo {@code integration:execute:<chave>} é
 * exigido e a operação é executada.
 */
@RestController
class ExecutionController {

  private static final String SCOPE_PREFIX = "SCOPE_integration:execute:";

  private final PublishedRoutes routes;
  private final ReadEngine reads;

  ExecutionController(PublishedRoutes routes, ReadEngine reads) {
    this.routes = routes;
    this.reads = reads;
  }

  @RequestMapping("/{slug}/api/**")
  @PreAuthorize("hasRole('CLIENT')")
  ResponseEntity<Object> execute(HttpServletRequest request, Authentication authentication) {
    TenantScope scope = TenantContext.require();
    List<String> path = pathSegments(request, scope.tenant().value());
    RouteTable.Resolution resolution = routes.resolve(scope, request.getMethod(), path);
    if (resolution.match().isEmpty()) {
      throw routeProblem(resolution);
    }
    RouteMatch match = resolution.match().get();
    String key = match.integration().key();
    if (authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .noneMatch((SCOPE_PREFIX + key)::equals)) {
      throw new ProblemException(
          403, "missing-scope", "Acesso negado", "O token não tem o escopo desta integração");
    }
    if (!"SELECT".equals(match.integration().snapshot().operation().type())) {
      throw new ProblemException(
          501, "not-implemented", "Não implementado", "Esta operação ainda não está disponível");
    }
    ReadResult result =
        reads.execute(scope, match.integration(), match.pathVariables(), queryOf(request));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.body());
  }

  private static ProblemException routeProblem(RouteTable.Resolution resolution) {
    if (resolution.allowedMethods().isEmpty()) {
      return new ProblemException(
          404,
          "route-not-found",
          "Rota não encontrada",
          "Nenhuma integração publicada atende este caminho");
    }
    return new ProblemException(
        405,
        "method-not-allowed",
        "Método não permitido",
        "O caminho existe, mas não para este método",
        List.of(),
        Map.of(
            "Allow",
            String.join(", ", Set.copyOf(resolution.allowedMethods()).stream().sorted().toList())));
  }

  /**
   * Segmentos do caminho depois de {@code /{tenant}/api}, decodificados; caminhos suspeitos viram
   * "não encontrado".
   */
  private static List<String> pathSegments(HttpServletRequest request, String tenant) {
    String uri = request.getRequestURI().substring(request.getContextPath().length());
    String rest = uri.substring(("/" + tenant + "/api").length());
    List<String> segments = new ArrayList<>();
    if (rest.isEmpty() || rest.equals("/")) {
      return segments;
    }
    String[] parts = rest.substring(1).split("/", -1);
    int count = parts.length;
    if (count > 0 && parts[count - 1].isEmpty()) {
      count--; // uma barra final é tolerada
    }
    for (int i = 0; i < count; i++) {
      String decoded = UriUtils.decode(parts[i], StandardCharsets.UTF_8);
      if (decoded.isEmpty()
          || decoded.equals(".")
          || decoded.equals("..")
          || decoded.contains("/")
          || decoded.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
        throw new ProblemException(
            404,
            "route-not-found",
            "Rota não encontrada",
            "Nenhuma integração publicada atende este caminho");
      }
      segments.add(decoded);
    }
    return segments;
  }

  private static Map<String, List<String>> queryOf(HttpServletRequest request) {
    Map<String, List<String>> query = new LinkedHashMap<>();
    request.getParameterMap().forEach((name, values) -> query.put(name, List.of(values)));
    return query;
  }
}
