package br.com.liberty.web;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.engine.lifecycle.LifecycleService;
import br.com.liberty.engine.lifecycle.LifecycleService.IntegrationView;
import br.com.liberty.engine.lifecycle.LifecycleService.VersionView;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.sandbox.SandboxService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * API de administração do ciclo de vida das integrações, para usuários (OIDC). INTEGRATOR escreve
 * rascunhos e envia para revisão; PUBLISHER revisa, publica por ambiente, reverte e despublica;
 * VIEWER consulta. As regras de negócio (quatro olhos, HML antes de PRD) ficam no serviço.
 */
@RestController
@RequestMapping("/api/v1/tenants/{slug}")
class LifecycleController {

  /** Corpo de criação de integração. */
  record CreateRequest(String key, String name, String method, String route) {}

  /** Entrada de uma execução de teste: parâmetros declarados e corpo. */
  record SandboxRequest(Map<String, JsonNode> params, JsonNode body) {}

  /** Comentário de revisão ou publicação. */
  record CommentRequest(String comment) {}

  private final LifecycleService service;
  private final SandboxService sandbox;

  LifecycleController(LifecycleService service, SandboxService sandbox) {
    this.service = service;
    this.sandbox = sandbox;
  }

  @GetMapping("/integrations")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  List<IntegrationView> list(@PathVariable String slug) {
    return service.list(slug);
  }

  @PostMapping("/integrations")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  ResponseEntity<IntegrationView> create(
      @PathVariable String slug, @RequestBody CreateRequest body, Authentication user) {
    var created =
        service.create(slug, body.key(), body.name(), body.method(), body.route(), user.getName());
    return ResponseEntity.status(201).body(created);
  }

  @GetMapping("/integrations/{key}/versions")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  List<VersionView> versions(@PathVariable String slug, @PathVariable String key) {
    return service.versions(slug, key);
  }

  @GetMapping(
      value = "/integrations/{key}/versions/{no}/snapshot",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  ResponseEntity<String> snapshot(
      @PathVariable String slug, @PathVariable String key, @PathVariable int no) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.snapshot(slug, key, no));
  }

  @PutMapping(value = "/integrations/{key}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  VersionView saveDraft(
      @PathVariable String slug,
      @PathVariable String key,
      @RequestBody byte[] snapshot,
      Authentication user) {
    return service.saveDraft(slug, key, snapshot, user.getName());
  }

  @PostMapping("/integrations/{key}/versions/{no}/submit")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  VersionView submit(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable int no,
      Authentication user) {
    return service.submit(slug, key, no, user.getName());
  }

  @PostMapping("/integrations/{key}/versions/{no}/reject")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  VersionView reject(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable int no,
      @RequestBody(required = false) CommentRequest body,
      Authentication user) {
    return service.reject(slug, key, no, user.getName(), body == null ? null : body.comment());
  }

  @PostMapping("/integrations/{key}/versions/{no}/publish/{env}")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  VersionView publish(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable int no,
      @PathVariable String env,
      @RequestBody(required = false) CommentRequest body,
      Authentication user) {
    return service.publish(
        slug, key, no, environment(env), user.getName(), body == null ? null : body.comment());
  }

  /** Executa a versão em HML com a transação sempre desfeita; devolve resultado, SQL e logs. */
  @PostMapping("/integrations/{key}/versions/{no}/sandbox")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  SandboxService.Result sandbox(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable int no,
      @RequestBody(required = false) SandboxRequest body) {
    return sandbox.run(
        slug, key, no, body == null ? null : body.params(), body == null ? null : body.body());
  }

  @PostMapping("/integrations/{key}/environments/{env}/rollback")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  VersionView rollback(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable String env,
      Authentication user) {
    return service.rollback(slug, key, environment(env), user.getName());
  }

  @DeleteMapping("/integrations/{key}/environments/{env}/publication")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'PUBLISHER')")
  ResponseEntity<Void> unpublish(
      @PathVariable String slug,
      @PathVariable String key,
      @PathVariable String env,
      Authentication user) {
    service.unpublish(slug, key, environment(env), user.getName());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/environments/{env}/catalog/tables")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  List<LifecycleService.TableView> catalogTables(
      @PathVariable String slug, @PathVariable String env) {
    return service.catalogTables(slug, environment(env));
  }

  @GetMapping("/environments/{env}/catalog/tables/{table:.+}")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'INTEGRATOR')")
  LifecycleService.TableDetail catalogTable(
      @PathVariable String slug, @PathVariable String env, @PathVariable String table) {
    return service.catalogTable(slug, environment(env), table);
  }

  @GetMapping("/environments/{env}/openapi.json")
  @PreAuthorize("@tenantAccess.hasRole(authentication, #slug, 'VIEWER')")
  ObjectNode openApi(@PathVariable String slug, @PathVariable String env) {
    return service.openApi(slug, environment(env));
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
