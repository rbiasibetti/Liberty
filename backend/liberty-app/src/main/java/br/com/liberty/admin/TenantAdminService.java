package br.com.liberty.admin;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantRole;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.persistence.ApiClientEntity;
import br.com.liberty.infrastructure.persistence.ApiClientRepository;
import br.com.liberty.infrastructure.persistence.TenantConnectionEntity;
import br.com.liberty.infrastructure.persistence.TenantConnectionRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantHttpHostEntity;
import br.com.liberty.infrastructure.persistence.TenantHttpHostRepository;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.persistence.UserRoleEntity;
import br.com.liberty.infrastructure.persistence.UserRoleRepository;
import br.com.liberty.infrastructure.tenancy.ConnectionTester;
import br.com.liberty.infrastructure.tenancy.SecretProvider;
import br.com.liberty.infrastructure.tenancy.SecretWriter;
import br.com.liberty.infrastructure.tenancy.TenantPoolRegistry;
import br.com.liberty.infrastructure.tenancy.TenantUnavailableException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administração de um tenant pelo administrador da plataforma: conexões com os bancos (a senha vai
 * direto para o cofre e nunca volta), clientes OAuth2 de sistema (o segredo aparece uma única vez,
 * na criação) e papéis dos usuários. Toda ação fica no log com quem a fez; segredos nunca.
 */
@Service
class TenantAdminService {

  private static final Logger LOG = LoggerFactory.getLogger(TenantAdminService.class);
  private static final Pattern CLIENT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{2,60}");
  private static final Pattern SCOPE = Pattern.compile("integration:execute:[a-z0-9-]{1,100}");
  private static final Pattern DB_USER = Pattern.compile("[A-Za-z0-9_.@-]{1,128}");
  private static final Pattern HTTP_HOST =
      Pattern.compile(
          "[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)+(:[0-9]{2,5})?");
  private static final Pattern IPV4_LIKE = Pattern.compile("[0-9.]+(:[0-9]+)?");
  private static final Pattern SUB = Pattern.compile("[A-Za-z0-9._@:-]{1,200}");
  private static final SecureRandom RANDOM = new SecureRandom();

  record ConnectionView(
      String environment, String jdbcUrl, String dbUsername, int poolMax, String status) {}

  record ConnectionRequest(String jdbcUrl, String dbUsername, String password, Integer poolMax) {}

  record ClientView(
      String clientId,
      String environment,
      List<String> scopes,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant revokedAt) {}

  record ClientRequest(String clientId, String environment, List<String> scopes) {}

  record CreatedClient(ClientView client, String clientSecret) {}

  record UserView(String sub, List<String> roles) {}

  record HttpHostView(String host, String createdBy, Instant createdAt) {}

  private final TenantRepository tenants;
  private final TenantConnectionRepository connections;
  private final ApiClientRepository clients;
  private final UserRoleRepository roles;
  private final TenantHttpHostRepository httpHosts;
  private final SecretProvider secrets;
  private final SecretWriter secretWriter;
  private final TenantPoolRegistry pools;
  private final TenantCatalog catalog;
  private final PasswordEncoder encoder;

  TenantAdminService(
      TenantRepository tenants,
      TenantConnectionRepository connections,
      ApiClientRepository clients,
      UserRoleRepository roles,
      TenantHttpHostRepository httpHosts,
      SecretProvider secrets,
      SecretWriter secretWriter,
      TenantPoolRegistry pools,
      TenantCatalog catalog,
      PasswordEncoder encoder) {
    this.tenants = tenants;
    this.connections = connections;
    this.clients = clients;
    this.roles = roles;
    this.httpHosts = httpHosts;
    this.secrets = secrets;
    this.secretWriter = secretWriter;
    this.pools = pools;
    this.catalog = catalog;
    this.encoder = encoder;
  }

  // ---- conexões --------------------------------------------------------------------------

  @Transactional(readOnly = true)
  List<ConnectionView> connections(String slug) {
    TenantEntity tenant = tenant(slug);
    List<ConnectionView> result = new ArrayList<>();
    for (EnvironmentKind env : EnvironmentKind.values()) {
      connections
          .findByTenantIdAndEnvironment(tenant.getId(), env)
          .ifPresent(c -> result.add(view(c)));
    }
    return result;
  }

  @Transactional
  ConnectionView saveConnection(
      String slug, EnvironmentKind env, ConnectionRequest request, String admin) {
    TenantEntity tenant = tenant(slug);
    List<Violation> problems = new ArrayList<>();
    try {
      ConnectionTester.validateUrl(request.jdbcUrl());
    } catch (TenantUnavailableException e) {
      problems.add(new Violation("jdbcUrl", "invalid_value", e.getMessage()));
    }
    if (request.dbUsername() == null || !DB_USER.matcher(request.dbUsername()).matches()) {
      problems.add(new Violation("dbUsername", "invalid_value", "Usuário do banco inválido"));
    }
    int poolMax = request.poolMax() == null ? 5 : request.poolMax();
    if (poolMax < 1 || poolMax > 20) {
      problems.add(new Violation("poolMax", "out_of_range", "O pool vai de 1 a 20 conexões"));
    }
    var existing = connections.findByTenantIdAndEnvironment(tenant.getId(), env);
    boolean hasPassword = request.password() != null && !request.password().isEmpty();
    if (existing.isEmpty() && !hasPassword) {
      problems.add(new Violation("password", "required", "Informe a senha do banco na criação"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    String reference =
        "liberty/tenants/%s/%s".formatted(slug, env.name().toLowerCase(java.util.Locale.ROOT));
    if (hasPassword) {
      try {
        secretWriter.writePassword(reference, request.password());
      } catch (TenantUnavailableException e) {
        throw new ProblemException(
            503, "vault-unavailable", "Serviço indisponível", "O cofre de segredos não respondeu");
      }
    }
    TenantConnectionEntity entity =
        existing.orElseGet(
            () ->
                new TenantConnectionEntity(
                    tenant.getId(),
                    env,
                    request.jdbcUrl(),
                    request.dbUsername(),
                    reference,
                    poolMax));
    entity.setJdbcUrl(request.jdbcUrl());
    entity.setDbUsername(request.dbUsername());
    entity.setSecretRef(reference);
    entity.setPoolMax(poolMax);
    TenantConnectionEntity saved = connections.save(entity);
    TenantScope scope = new TenantScope(new TenantId(slug), env);
    pools.evict(scope);
    catalog.invalidate(scope);
    LOG.info(
        "Conexão {} de {} salva por {} (senha {})",
        env,
        slug,
        admin,
        hasPassword ? "trocada" : "mantida");
    return view(saved);
  }

  /** Abre uma conexão de verdade com os dados salvos (senha lida do cofre) e roda SELECT 1. */
  @Transactional(readOnly = true)
  ConnectionTester.Result testConnection(String slug, EnvironmentKind env) {
    TenantEntity tenant = tenant(slug);
    TenantConnectionEntity connection =
        connections
            .findByTenantIdAndEnvironment(tenant.getId(), env)
            .orElseThrow(
                () ->
                    new ProblemException(
                        404,
                        "not-found",
                        "Não encontrado",
                        "Conexão não cadastrada neste ambiente"));
    String password;
    try {
      password = secrets.password(connection.getSecretRef());
    } catch (TenantUnavailableException e) {
      return new ConnectionTester.Result(false, 0, "Senha indisponível no cofre de segredos");
    }
    return ConnectionTester.test(connection.getJdbcUrl(), connection.getDbUsername(), password);
  }

  // ---- clientes OAuth2 -------------------------------------------------------------------

  @Transactional(readOnly = true)
  List<ClientView> clients(String slug) {
    TenantEntity tenant = tenant(slug);
    return clients.findByTenantIdOrderByClientId(tenant.getId()).stream()
        .map(TenantAdminService::view)
        .toList();
  }

  @Transactional
  CreatedClient createClient(String slug, ClientRequest request, String admin) {
    TenantEntity tenant = tenant(slug);
    List<Violation> problems = new ArrayList<>();
    if (request.clientId() == null || !CLIENT_ID.matcher(request.clientId()).matches()) {
      problems.add(
          new Violation(
              "clientId", "invalid_value", "Use de 3 a 61 letras minúsculas, números e hífen"));
    }
    EnvironmentKind env = null;
    try {
      env =
          EnvironmentKind.valueOf(
              String.valueOf(request.environment()).toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      problems.add(new Violation("environment", "invalid_value", "Ambiente deve ser HML ou PRD"));
    }
    List<String> scopes = request.scopes() == null ? List.of() : request.scopes();
    if (scopes.isEmpty()
        || scopes.size() > 50
        || !scopes.stream().allMatch(s -> s != null && SCOPE.matcher(s).matches())) {
      problems.add(
          new Violation(
              "scopes", "invalid_value", "Informe de 1 a 50 escopos integration:execute:<chave>"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    if (clients.findByClientId(request.clientId()).isPresent()) {
      throw new ProblemException(
          409, "client-exists", "Conflito", "Já existe um cliente com este identificador");
    }
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    ApiClientEntity saved =
        clients.save(
            new ApiClientEntity(
                tenant.getId(),
                request.clientId(),
                encoder.encode(secret),
                env,
                String.join(" ", scopes.stream().distinct().sorted().toList())));
    LOG.info("Cliente {} ({}) criado em {} por {}", request.clientId(), env, slug, admin);
    return new CreatedClient(view(saved), secret);
  }

  @Transactional
  void revokeClient(String slug, String clientId, String admin) {
    TenantEntity tenant = tenant(slug);
    ApiClientEntity client =
        clients
            .findByClientId(clientId)
            .filter(c -> c.getTenantId().equals(tenant.getId()))
            .orElseThrow(
                () ->
                    new ProblemException(
                        404, "not-found", "Não encontrado", "Cliente não encontrado"));
    client.setStatus("REVOKED");
    client.setRevokedAt(Instant.now());
    clients.save(client);
    LOG.info("Cliente {} revogado em {} por {}", clientId, slug, admin);
  }

  // ---- usuários e papéis -----------------------------------------------------------------

  @Transactional(readOnly = true)
  List<UserView> users(String slug) {
    TenantEntity tenant = tenant(slug);
    Map<String, List<String>> bySub = new TreeMap<>();
    for (UserRoleEntity role : roles.findByIdTenantId(tenant.getId())) {
      bySub
          .computeIfAbsent(role.getId().userSub(), k -> new ArrayList<>())
          .add(role.getId().role().name());
    }
    return bySub.entrySet().stream()
        .map(e -> new UserView(e.getKey(), e.getValue().stream().sorted().toList()))
        .toList();
  }

  @Transactional
  void grant(String slug, String sub, String role, String admin) {
    TenantEntity tenant = tenant(slug);
    var key = key(tenant, sub, role);
    if (roles.findById(key).isEmpty()) {
      roles.save(new UserRoleEntity(sub, tenant.getId(), key.role()));
      LOG.info("Papel {} concedido a {} em {} por {}", role, sub, slug, admin);
    }
  }

  @Transactional
  void revoke(String slug, String sub, String role, String admin) {
    TenantEntity tenant = tenant(slug);
    var key = key(tenant, sub, role);
    roles
        .findById(key)
        .ifPresent(
            entity -> {
              roles.delete(entity);
              LOG.info("Papel {} retirado de {} em {} por {}", role, sub, slug, admin);
            });
  }

  // ---- hosts de saída (http.fetch) -------------------------------------------------------

  @Transactional(readOnly = true)
  List<HttpHostView> httpHosts(String slug) {
    TenantEntity tenant = tenant(slug);
    return httpHosts.findByIdTenantIdOrderByIdHost(tenant.getId()).stream()
        .map(h -> new HttpHostView(h.getId().host(), h.getCreatedBy(), h.getCreatedAt()))
        .toList();
  }

  @Transactional
  void allowHttpHost(String slug, String host, String admin) {
    TenantEntity tenant = tenant(slug);
    String normalized = host == null ? "" : host;
    if (normalized.length() > 260
        || IPV4_LIKE.matcher(normalized).matches()
        || !HTTP_HOST.matcher(normalized).matches()) {
      throw ProblemException.validation(
          List.of(
              new Violation(
                  "host",
                  "invalid_value",
                  "Use um nome DNS em minúsculas, com :porta se não for a 443; IP não é aceito")));
    }
    var key = new TenantHttpHostEntity.Key(tenant.getId(), normalized);
    if (httpHosts.findById(key).isEmpty()) {
      httpHosts.save(new TenantHttpHostEntity(tenant.getId(), normalized, admin));
      LOG.info("Host de saída {} liberado em {} por {}", normalized, slug, admin);
    }
  }

  @Transactional
  void removeHttpHost(String slug, String host, String admin) {
    TenantEntity tenant = tenant(slug);
    httpHosts
        .findById(new TenantHttpHostEntity.Key(tenant.getId(), host))
        .ifPresent(
            entity -> {
              httpHosts.delete(entity);
              LOG.info("Host de saída {} retirado de {} por {}", host, slug, admin);
            });
  }

  // ---- auxiliares ------------------------------------------------------------------------

  private UserRoleEntity.Key key(TenantEntity tenant, String sub, String role) {
    List<Violation> problems = new ArrayList<>();
    if (sub == null || !SUB.matcher(sub).matches()) {
      problems.add(new Violation("sub", "invalid_value", "Identificador de usuário inválido"));
    }
    TenantRole parsed = null;
    try {
      parsed = TenantRole.valueOf(String.valueOf(role));
    } catch (IllegalArgumentException e) {
      problems.add(
          new Violation("role", "invalid_value", "Use " + Arrays.toString(TenantRole.values())));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    return new UserRoleEntity.Key(sub, tenant.getId(), parsed);
  }

  private TenantEntity tenant(String slug) {
    return tenants
        .findBySlug(slug)
        .orElseThrow(
            () ->
                new ProblemException(404, "not-found", "Não encontrado", "Tenant não encontrado"));
  }

  private static ConnectionView view(TenantConnectionEntity c) {
    return new ConnectionView(
        c.getEnvironment().name(),
        c.getJdbcUrl(),
        c.getDbUsername(),
        c.getPoolMax(),
        c.getStatus());
  }

  private static ClientView view(ApiClientEntity c) {
    return new ClientView(
        c.getClientId(),
        c.getEnvironment().name(),
        Arrays.asList(c.getScopes().split(" ")),
        c.getStatus(),
        c.getCreatedAt(),
        c.getExpiresAt(),
        c.getRevokedAt());
  }
}
