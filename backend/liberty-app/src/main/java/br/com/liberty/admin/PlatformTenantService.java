package br.com.liberty.admin;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.engine.traffic.LibertyLimitProperties;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.tenancy.ConnectionTester;
import br.com.liberty.infrastructure.tenancy.SecretProvider;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import br.com.liberty.infrastructure.tenancy.TenantUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciclo de vida dos tenants pelo administrador da plataforma: criar, listar, trocar nome, plano e
 * estado, e conferir se um tenant está pronto para uso (checklist de onboarding). Suspender ou
 * arquivar derruba na hora o que o tenant já tinha em memória (pools, tabela de rotas); o resto
 * (usuários, clientes e conexões) já respeita o estado a cada decisão. A plataforma não cria o
 * banco do cliente: ele é do cliente (ADR 0020).
 */
@Service
class PlatformTenantService {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformTenantService.class);
  private static final String DEFAULT_PLAN = "STANDARD";
  private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "ARCHIVED");

  record TenantView(String slug, String name, String planCode, String status, Instant createdAt) {}

  record CreateRequest(String slug, String name, String planCode) {}

  record UpdateRequest(String name, String planCode, String status) {}

  /**
   * Um item do checklist: {@code OK}, {@code PENDING} (falta fazer), {@code WARN} ou {@code FAIL}.
   */
  record Check(String id, String label, String status, String detail) {}

  record Onboarding(String slug, boolean ready, List<Check> checks) {}

  private final TenantRepository tenants;
  private final TenantConnectionRepository connections;
  private final ApiClientRepository clients;
  private final UserRoleRepository roles;
  private final SecretProvider secrets;
  private final TenantPoolRegistry pools;
  private final PublishedRoutes routes;
  private final LibertyLimitProperties limits;

  PlatformTenantService(
      TenantRepository tenants,
      TenantConnectionRepository connections,
      ApiClientRepository clients,
      UserRoleRepository roles,
      SecretProvider secrets,
      TenantPoolRegistry pools,
      PublishedRoutes routes,
      LibertyLimitProperties limits) {
    this.tenants = tenants;
    this.connections = connections;
    this.clients = clients;
    this.roles = roles;
    this.secrets = secrets;
    this.pools = pools;
    this.routes = routes;
    this.limits = limits;
  }

  /** Planos aceitos: o padrão e os configurados em {@code liberty.limits.plans}. */
  Set<String> plans() {
    Set<String> plans = new TreeSet<>(limits.plans().keySet());
    plans.add(DEFAULT_PLAN);
    return plans;
  }

  @Transactional(readOnly = true)
  List<TenantView> list() {
    return tenants.findAllByOrderBySlugAsc().stream().map(PlatformTenantService::view).toList();
  }

  @Transactional
  TenantView create(CreateRequest request, String admin) {
    List<Violation> problems = new ArrayList<>();
    String slug = request.slug() == null ? "" : request.slug();
    try {
      new TenantId(slug);
    } catch (IllegalArgumentException e) {
      problems.add(
          new Violation(
              "slug",
              "invalid_value",
              "Use de 2 a 63 letras minúsculas, números e hífen (sem hífen no início) e um nome não reservado"));
    }
    String name = request.name() == null ? "" : request.name().strip();
    if (name.isEmpty() || name.length() > 200) {
      problems.add(new Violation("name", "invalid_value", "Informe um nome de até 200 caracteres"));
    }
    String plan =
        request.planCode() == null || request.planCode().isBlank()
            ? DEFAULT_PLAN
            : request.planCode();
    if (!plans().contains(plan)) {
      problems.add(
          new Violation("planCode", "invalid_value", "Plano desconhecido; use " + plans()));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    if (tenants.findBySlug(slug).isPresent()) {
      throw conflict("tenant-exists", "Já existe um tenant com este identificador");
    }
    try {
      TenantEntity saved = tenants.save(new TenantEntity(slug, name, plan));
      LOG.info("Tenant {} criado por {} (plano {})", slug, admin, plan);
      return view(saved);
    } catch (DataIntegrityViolationException e) {
      throw conflict("tenant-exists", "Já existe um tenant com este identificador");
    }
  }

  @Transactional
  TenantView update(String slug, UpdateRequest request, String admin) {
    TenantEntity tenant = tenant(slug);
    if ("ARCHIVED".equals(tenant.getStatus())) {
      throw conflict("tenant-archived", "Um tenant arquivado não pode ser alterado");
    }
    List<Violation> problems = new ArrayList<>();
    if (request.name() != null
        && (request.name().isBlank() || request.name().strip().length() > 200)) {
      problems.add(new Violation("name", "invalid_value", "Informe um nome de até 200 caracteres"));
    }
    if (request.planCode() != null && !plans().contains(request.planCode())) {
      problems.add(
          new Violation("planCode", "invalid_value", "Plano desconhecido; use " + plans()));
    }
    if (request.status() != null && !STATUSES.contains(request.status())) {
      problems.add(new Violation("status", "invalid_value", "Use ACTIVE, SUSPENDED ou ARCHIVED"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    boolean leaving = false;
    if (request.name() != null) {
      tenant.setName(request.name().strip());
    }
    if (request.planCode() != null && !request.planCode().equals(tenant.getPlanCode())) {
      LOG.info(
          "Plano de {} de {} para {} por {}",
          slug,
          tenant.getPlanCode(),
          request.planCode(),
          admin);
      tenant.setPlanCode(request.planCode());
    }
    if (request.status() != null && !request.status().equals(tenant.getStatus())) {
      LOG.info(
          "Estado de {} de {} para {} por {}", slug, tenant.getStatus(), request.status(), admin);
      tenant.setStatus(request.status());
      leaving = !"ACTIVE".equals(request.status());
    }
    TenantEntity saved = tenants.save(tenant);
    if (leaving) {
      for (EnvironmentKind env : EnvironmentKind.values()) {
        TenantScope scope = new TenantScope(new TenantId(slug), env);
        pools.evict(scope);
        routes.invalidate(scope);
      }
    }
    return view(saved);
  }

  /** Confere o que falta para o tenant ser usado; cada item diz o que fazer. */
  @Transactional(readOnly = true)
  Onboarding onboarding(String slug) {
    TenantEntity tenant = tenant(slug);
    List<Check> checks = new ArrayList<>();
    boolean active = "ACTIVE".equals(tenant.getStatus());
    checks.add(
        new Check(
            "tenant-ativo",
            "Tenant ativo",
            active ? "OK" : "FAIL",
            active ? "O tenant está ativo" : "O tenant está " + tenant.getStatus()));
    for (EnvironmentKind env : EnvironmentKind.values()) {
      checks.addAll(connectionChecks(tenant, env));
    }
    Set<String> integrators = new LinkedHashSet<>();
    Set<String> publishers = new LinkedHashSet<>();
    for (UserRoleEntity role : roles.findByIdTenantId(tenant.getId())) {
      if (role.getId().role() == TenantRole.INTEGRATOR) {
        integrators.add(role.getId().userSub());
      } else if (role.getId().role() == TenantRole.PUBLISHER) {
        publishers.add(role.getId().userSub());
      }
    }
    boolean fourEyes =
        integrators.stream().anyMatch(i -> publishers.stream().anyMatch(p -> !p.equals(i)));
    checks.add(
        new Check(
            "quatro-olhos",
            "Um integrador e um publicador diferentes",
            fourEyes ? "OK" : "PENDING",
            fourEyes
                ? "Há quem escreva e quem aprove"
                : "Conceda INTEGRATOR a uma pessoa e PUBLISHER a outra: o autor nunca aprova a própria versão"));
    boolean hasClient =
        clients.findByTenantIdOrderByClientId(tenant.getId()).stream()
            .anyMatch(c -> "ACTIVE".equals(c.getStatus()));
    checks.add(
        new Check(
            "cliente-oauth2",
            "Cliente OAuth2 de sistema",
            hasClient ? "OK" : "PENDING",
            hasClient
                ? "Há um cliente ativo"
                : "Crie um cliente para os sistemas que vão chamar a API"));
    boolean ready =
        checks.stream().allMatch(c -> c.status().equals("OK") || c.status().equals("WARN"));
    return new Onboarding(slug, ready, checks);
  }

  private List<Check> connectionChecks(TenantEntity tenant, EnvironmentKind env) {
    String name = env.name();
    String id = name.toLowerCase(java.util.Locale.ROOT);
    Optional<TenantConnectionEntity> found =
        connections.findByTenantIdAndEnvironment(tenant.getId(), env);
    if (found.isEmpty()) {
      return List.of(
          new Check(
              "conexao-" + id,
              "Conexão " + name,
              "PENDING",
              "Cadastre a conexão com o banco do cliente em " + name));
    }
    TenantConnectionEntity connection = found.get();
    String password;
    try {
      password = secrets.password(connection.getSecretRef());
    } catch (TenantUnavailableException e) {
      return List.of(
          new Check(
              "conexao-" + id,
              "Conexão " + name,
              "FAIL",
              "Senha indisponível no cofre de segredos"));
    }
    var result =
        ConnectionTester.test(connection.getJdbcUrl(), connection.getDbUsername(), password);
    List<Check> checks = new ArrayList<>();
    checks.add(
        new Check(
            "conexao-" + id,
            "Conexão " + name,
            result.ok() ? "OK" : "FAIL",
            result.ok() ? "Conectou em " + result.millis() + " ms" : result.message()));
    if (result.ok()) {
      List<String> risks =
          ConnectionTester.risks(connection.getJdbcUrl(), connection.getDbUsername(), password);
      boolean excessive = risks != null && !risks.isEmpty();
      checks.add(
          new Check(
              "privilegios-" + id,
              "Privilégios do usuário do banco em " + name,
              risks == null ? "WARN" : excessive ? "WARN" : "OK",
              risks == null
                  ? "Não foi possível verificar os privilégios"
                  : excessive
                      ? "O usuário tem privilégio excessivo ("
                          + String.join(", ", risks)
                          + "): use um usuário só com leitura e escrita nas tabelas das integrações"
                      : "Sem papéis administrativos"));
    }
    return checks;
  }

  private TenantEntity tenant(String slug) {
    return tenants
        .findBySlug(slug)
        .orElseThrow(
            () ->
                new ProblemException(404, "not-found", "Não encontrado", "Tenant não encontrado"));
  }

  private static ProblemException conflict(String type, String detail) {
    return new ProblemException(409, type, "Conflito", detail);
  }

  private static TenantView view(TenantEntity t) {
    return new TenantView(
        t.getSlug(), t.getName(), t.getPlanCode(), t.getStatus(), t.getCreatedAt());
  }
}
