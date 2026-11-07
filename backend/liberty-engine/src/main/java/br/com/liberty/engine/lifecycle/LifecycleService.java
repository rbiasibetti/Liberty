package br.com.liberty.engine.lifecycle;

import br.com.liberty.domain.integration.VersionState;
import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.engine.route.PublishedRoutes;
import br.com.liberty.engine.route.RoutePattern;
import br.com.liberty.infrastructure.catalog.TenantCatalog;
import br.com.liberty.infrastructure.catalog.UnknownDatabaseObjectException;
import br.com.liberty.infrastructure.persistence.IntegrationEntity;
import br.com.liberty.infrastructure.persistence.IntegrationRepository;
import br.com.liberty.infrastructure.persistence.IntegrationVersionEntity;
import br.com.liberty.infrastructure.persistence.IntegrationVersionRepository;
import br.com.liberty.infrastructure.persistence.PublicationEntity;
import br.com.liberty.infrastructure.persistence.PublicationRepository;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.infrastructure.tenancy.TenantUnavailableException;
import br.com.liberty.snapshot.ParsedSnapshot;
import br.com.liberty.snapshot.SnapshotService;
import br.com.liberty.snapshot.model.Snapshot;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ciclo de vida das integrações: rascunho, revisão, publicação por ambiente e rollback. Quem
 * escreve a versão não a aprova (também imposto por restrição no banco); uma versão só chega a PRD
 * depois de publicada em HML; publicar e reverter valem na hora na instância e em até 5 s nas
 * demais. Os papéis (INTEGRATOR e PUBLISHER) são conferidos na borda HTTP; aqui ficam as regras de
 * negócio. Toda ação é registrada em log com o usuário (a trilha de auditoria completa é da E17).
 */
public class LifecycleService {

  private static final Logger LOG = LoggerFactory.getLogger(LifecycleService.class);

  /** Integração cadastrada. */
  public record IntegrationView(
      String key, String name, String method, String route, boolean archived) {}

  /** Versão de uma integração. */
  public record VersionView(
      int versionNo,
      String state,
      String author,
      String reviewer,
      String comment,
      Instant createdAt,
      Instant reviewedAt,
      String sha256) {}

  private final TenantRepository tenants;
  private final IntegrationRepository integrations;
  private final IntegrationVersionRepository versions;
  private final PublicationRepository publications;
  private final SnapshotService snapshots;
  private final TenantCatalog catalog;
  private final PublishedRoutes routes;
  private final EntityManager entityManager;

  public LifecycleService(
      TenantRepository tenants,
      IntegrationRepository integrations,
      IntegrationVersionRepository versions,
      PublicationRepository publications,
      SnapshotService snapshots,
      TenantCatalog catalog,
      PublishedRoutes routes,
      EntityManager entityManager) {
    this.tenants = tenants;
    this.integrations = integrations;
    this.versions = versions;
    this.publications = publications;
    this.snapshots = snapshots;
    this.catalog = catalog;
    this.routes = routes;
    this.entityManager = entityManager;
  }

  // ---- cadastro e consulta ---------------------------------------------------------------

  @Transactional
  public IntegrationView create(
      String slug, String key, String name, String method, String route, String user) {
    TenantEntity tenant = tenant(slug);
    List<Violation> problems = new ArrayList<>();
    if (key == null || !key.matches("[a-z0-9-]{1,100}")) {
      problems.add(
          new Violation(
              "key", "invalid_value", "Use de 1 a 100 letras minúsculas, números e hífen"));
    }
    if (name == null || name.isBlank() || name.length() > 255) {
      problems.add(new Violation("name", "invalid_value", "Informe um nome de até 255 caracteres"));
    }
    if (method == null || !List.of("GET", "POST", "PUT", "PATCH").contains(method)) {
      problems.add(new Violation("method", "invalid_value", "Use GET, POST, PUT ou PATCH"));
    }
    try {
      if (route == null) {
        throw new IllegalArgumentException();
      }
      RoutePattern.parse(route);
    } catch (RuntimeException e) {
      problems.add(new Violation("route", "invalid_value", "Rota inválida"));
    }
    if (!problems.isEmpty()) {
      throw ProblemException.validation(problems);
    }
    if (integrations.findByTenantIdAndIntegrationKey(tenant.getId(), key).isPresent()
        || integrations
            .findByTenantIdAndHttpMethodAndRoutePattern(tenant.getId(), method, route)
            .isPresent()) {
      throw conflict("integration-exists", "Já existe uma integração com esta chave ou rota");
    }
    try {
      IntegrationEntity saved =
          integrations.save(new IntegrationEntity(tenant.getId(), key, name, method, route));
      LOG.info("Integração {} criada em {} por {}", key, slug, user);
      return view(saved);
    } catch (DataIntegrityViolationException e) {
      throw conflict("integration-exists", "Já existe uma integração com esta chave ou rota");
    }
  }

  @Transactional(readOnly = true)
  public List<IntegrationView> list(String slug) {
    TenantEntity tenant = tenant(slug);
    return integrations.findByTenantIdAndArchivedFalseOrderByIntegrationKey(tenant.getId()).stream()
        .map(LifecycleService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<VersionView> versions(String slug, String key) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    return versions
        .findByIntegrationIdAndTenantIdOrderByVersionNoDesc(integration.getId(), tenant.getId())
        .stream()
        .map(LifecycleService::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public String snapshot(String slug, String key, int versionNo) {
    TenantEntity tenant = tenant(slug);
    return version(tenant, integration(tenant, key), versionNo).getSnapshot();
  }

  // ---- rascunho e revisão ----------------------------------------------------------------

  /** Cria a versão rascunho ou substitui o rascunho existente; devolve a versão. */
  @Transactional
  public VersionView saveDraft(String slug, String key, byte[] snapshotJson, String user) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    ParsedSnapshot parsed = parse(snapshotJson);
    List<Violation> problems =
        PublishChecks.check(
            parsed.model(), key, integration.getHttpMethod(), integration.getRoutePattern());
    if (!problems.isEmpty()) {
      throw unprocessable(problems);
    }
    var existing =
        versions.findByIntegrationIdAndTenantIdAndState(
            integration.getId(), tenant.getId(), VersionState.DRAFT);
    IntegrationVersionEntity draft;
    if (existing.isPresent()) {
      draft = existing.get();
      draft.setSnapshot(parsed.canonicalJson());
      draft.setSnapshotSha256(parsed.sha256());
    } else {
      int next = versions.lockAndGetMaxVersionNo(integration.getId()) + 1;
      draft =
          new IntegrationVersionEntity(
              tenant.getId(),
              integration.getId(),
              next,
              VersionState.DRAFT,
              parsed.canonicalJson(),
              parsed.sha256(),
              user);
    }
    LOG.info("Rascunho v{} de {}/{} salvo por {}", draft.getVersionNo(), slug, key, user);
    return view(versions.save(draft));
  }

  @Transactional
  public VersionView submit(String slug, String key, int versionNo, String user) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    IntegrationVersionEntity version = version(tenant, integration, versionNo);
    requireState(version, VersionState.DRAFT, "Só um rascunho pode ser enviado para revisão");
    ParsedSnapshot parsed = parse(version.getSnapshot().getBytes(StandardCharsets.UTF_8));
    List<Violation> problems =
        PublishChecks.check(
            parsed.model(), key, integration.getHttpMethod(), integration.getRoutePattern());
    if (!problems.isEmpty()) {
      throw unprocessable(problems);
    }
    version.setState(VersionState.IN_REVIEW);
    LOG.info("Versão {} de {}/{} enviada para revisão por {}", versionNo, slug, key, user);
    return view(versions.save(version));
  }

  @Transactional
  public VersionView reject(
      String slug, String key, int versionNo, String reviewer, String comment) {
    TenantEntity tenant = tenant(slug);
    IntegrationVersionEntity version = version(tenant, integration(tenant, key), versionNo);
    requireState(version, VersionState.IN_REVIEW, "Só uma versão em revisão pode ser rejeitada");
    requireOtherPerson(version, reviewer);
    version.setState(VersionState.REJECTED);
    version.setReviewerSub(reviewer);
    version.setReviewComment(limit(comment));
    version.setReviewedAt(Instant.now());
    LOG.info("Versão {} de {}/{} rejeitada por {}", versionNo, slug, key, reviewer);
    return view(versions.save(version));
  }

  // ---- publicação ---------------------------------------------------------------------------

  /**
   * Publica a versão no ambiente. Uma versão em revisão vira PUBLICADA na primeira publicação (o
   * revisor fica registrado); PRD exige publicação prévia em HML; o autor nunca publica a própria
   * versão.
   */
  @Transactional
  public VersionView publish(
      String slug,
      String key,
      int versionNo,
      EnvironmentKind environment,
      String user,
      String comment) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    IntegrationVersionEntity version = version(tenant, integration, versionNo);
    if (version.getState() != VersionState.IN_REVIEW
        && version.getState() != VersionState.PUBLISHED) {
      throw conflict(
          "invalid-state", "Só versões em revisão ou já publicadas podem ser publicadas");
    }
    requireOtherPerson(version, user);
    List<PublicationEntity> history =
        publications.findByTenantIdAndIntegrationIdAndEnvironmentOrderByIdDesc(
            tenant.getId(), integration.getId(), environment);
    if (environment == EnvironmentKind.PRD
        && publications
            .findByTenantIdAndIntegrationIdAndEnvironmentOrderByIdDesc(
                tenant.getId(), integration.getId(), EnvironmentKind.HML)
            .stream()
            .noneMatch(p -> p.getVersionId().equals(version.getId()))) {
      throw conflict("not-in-hml", "A versão precisa ser publicada em HML antes de ir para PRD");
    }
    ParsedSnapshot parsed = parse(version.getSnapshot().getBytes(StandardCharsets.UTF_8));
    TenantScope scope = new TenantScope(new TenantId(slug), environment);
    List<Violation> problems =
        PublishChecks.check(
            parsed.model(), key, integration.getHttpMethod(), integration.getRoutePattern());
    problems.addAll(checkCatalog(scope, parsed.model()));
    if (!problems.isEmpty()) {
      throw unprocessable(problems);
    }
    checkRouteConflict(tenant, integration, environment);
    if (version.getState() == VersionState.IN_REVIEW) {
      version.setState(VersionState.PUBLISHED);
      version.setReviewerSub(user);
      version.setReviewComment(limit(comment));
      version.setReviewedAt(Instant.now());
      versions.save(version);
    }
    activate(tenant, integration, version, environment, user, history);
    routes.invalidate(scope);
    LOG.info("Versão {} de {}/{} publicada em {} por {}", versionNo, slug, key, environment, user);
    return view(version);
  }

  /** Reativa a publicação anterior (com outra versão) no ambiente. */
  @Transactional
  public VersionView rollback(String slug, String key, EnvironmentKind environment, String user) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    List<PublicationEntity> history =
        publications.findByTenantIdAndIntegrationIdAndEnvironmentOrderByIdDesc(
            tenant.getId(), integration.getId(), environment);
    PublicationEntity current =
        history.stream()
            .filter(PublicationEntity::isActive)
            .findFirst()
            .orElseThrow(
                () -> conflict("not-published", "A integração não está publicada neste ambiente"));
    PublicationEntity previous =
        history.stream()
            .filter(
                p ->
                    p.getId() < current.getId() && !p.getVersionId().equals(current.getVersionId()))
            .findFirst()
            .orElseThrow(
                () -> conflict("no-previous-version", "Não há versão anterior para voltar"));
    IntegrationVersionEntity version =
        versions
            .findByIdAndTenantId(previous.getVersionId(), tenant.getId())
            .orElseThrow(
                () -> conflict("no-previous-version", "Não há versão anterior para voltar"));
    activate(tenant, integration, version, environment, user, history);
    routes.invalidate(new TenantScope(new TenantId(slug), environment));
    LOG.info(
        "Rollback de {}/{} em {} para a v{} por {}",
        slug,
        key,
        environment,
        version.getVersionNo(),
        user);
    return view(version);
  }

  @Transactional
  public void unpublish(String slug, String key, EnvironmentKind environment, String user) {
    TenantEntity tenant = tenant(slug);
    IntegrationEntity integration = integration(tenant, key);
    PublicationEntity current =
        publications
            .findByTenantIdAndIntegrationIdAndEnvironmentAndActiveTrue(
                tenant.getId(), integration.getId(), environment)
            .orElseThrow(
                () -> conflict("not-published", "A integração não está publicada neste ambiente"));
    current.setActive(false);
    publications.save(current);
    routes.invalidate(new TenantScope(new TenantId(slug), environment));
    LOG.info("{}/{} despublicada em {} por {}", slug, key, environment, user);
  }

  /** OpenAPI das integrações publicadas no ambiente. */
  @Transactional(readOnly = true)
  public ObjectNode openApi(String slug, EnvironmentKind environment) {
    TenantEntity tenant = tenant(slug);
    List<Snapshot> served = new ArrayList<>();
    for (var row : publications.findServed(tenant.getId(), environment)) {
      served.add(parse(row.snapshot().getBytes(StandardCharsets.UTF_8)).model());
    }
    return OpenApiGenerator.generate(slug, environment.name(), served);
  }

  // ---- auxiliares ------------------------------------------------------------------------

  private void activate(
      TenantEntity tenant,
      IntegrationEntity integration,
      IntegrationVersionEntity version,
      EnvironmentKind environment,
      String user,
      List<PublicationEntity> history) {
    history.stream()
        .filter(PublicationEntity::isActive)
        .forEach(
            p -> {
              p.setActive(false);
              publications.save(p);
            });
    // o índice único só admite uma publicação ativa: a desativação vai ao banco antes da nova
    entityManager.flush();
    publications.save(
        new PublicationEntity(
            tenant.getId(), integration.getId(), version.getId(), environment, true, user));
  }

  private List<Violation> checkCatalog(TenantScope scope, Snapshot snapshot) {
    List<Violation> problems = new ArrayList<>();
    for (String table : PublishChecks.tables(snapshot)) {
      try {
        catalog.table(scope, table);
      } catch (UnknownDatabaseObjectException e) {
        problems.add(
            new Violation(
                "/operation",
                "unknown_table",
                "Tabela inexistente no banco do ambiente: " + safe(table)));
      } catch (TenantUnavailableException e) {
        throw new ProblemException(
            503,
            "tenant-unavailable",
            "Serviço indisponível",
            "O banco do cliente está indisponível para validar a publicação");
      }
    }
    return problems;
  }

  private void checkRouteConflict(
      TenantEntity tenant, IntegrationEntity integration, EnvironmentKind environment) {
    String signature = RoutePattern.parse(integration.getRoutePattern()).signature();
    for (var row : publications.findServed(tenant.getId(), environment)) {
      if (!row.integrationId().equals(integration.getId())
          && row.httpMethod().equals(integration.getHttpMethod())
          && RoutePattern.parse(row.routePattern()).signature().equals(signature)) {
        throw conflict(
            "route-conflict",
            "Outra integração publicada neste ambiente atende a mesma rota e método");
      }
    }
  }

  private ParsedSnapshot parse(byte[] json) {
    try {
      return snapshots.parse(json);
    } catch (SnapshotValidationException e) {
      throw unprocessable(
          e.errors().stream()
              .limit(50)
              .map(f -> new Violation(f.path(), f.code(), f.message()))
              .toList());
    }
  }

  private TenantEntity tenant(String slug) {
    return tenants
        .findBySlug(slug)
        .filter(t -> "ACTIVE".equals(t.getStatus()))
        .orElseThrow(LifecycleService::notFound);
  }

  private IntegrationEntity integration(TenantEntity tenant, String key) {
    return integrations
        .findByTenantIdAndIntegrationKey(tenant.getId(), key)
        .filter(i -> !i.isArchived())
        .orElseThrow(LifecycleService::notFound);
  }

  private IntegrationVersionEntity version(
      TenantEntity tenant, IntegrationEntity integration, int versionNo) {
    return versions
        .findByIntegrationIdAndTenantIdOrderByVersionNoDesc(integration.getId(), tenant.getId())
        .stream()
        .filter(v -> v.getVersionNo() == versionNo)
        .findFirst()
        .orElseThrow(LifecycleService::notFound);
  }

  private static void requireState(
      IntegrationVersionEntity version, VersionState expected, String message) {
    if (version.getState() != expected) {
      throw conflict("invalid-state", message);
    }
  }

  private static void requireOtherPerson(IntegrationVersionEntity version, String user) {
    if (version.getAuthorSub().equals(user)) {
      throw new ProblemException(
          403,
          "four-eyes",
          "Acesso negado",
          "Quem escreveu a versão não pode revisá-la nem publicá-la");
    }
  }

  private static String limit(String comment) {
    if (comment == null) {
      return null;
    }
    return comment.length() > 2000 ? comment.substring(0, 2000) : comment;
  }

  private static String safe(String table) {
    return table.length() > 100 ? table.substring(0, 100) : table;
  }

  private static IntegrationView view(IntegrationEntity i) {
    return new IntegrationView(
        i.getIntegrationKey(), i.getName(), i.getHttpMethod(), i.getRoutePattern(), i.isArchived());
  }

  private static VersionView view(IntegrationVersionEntity v) {
    return new VersionView(
        v.getVersionNo(),
        v.getState().name(),
        v.getAuthorSub(),
        v.getReviewerSub(),
        v.getReviewComment(),
        v.getCreatedAt(),
        v.getReviewedAt(),
        HexFormat.of().formatHex(v.getSnapshotSha256()));
  }

  private static ProblemException notFound() {
    return new ProblemException(404, "not-found", "Não encontrado", "Recurso não encontrado");
  }

  private static ProblemException conflict(String type, String detail) {
    return new ProblemException(409, type, "Conflito", detail);
  }

  private static ProblemException unprocessable(List<Violation> violations) {
    return new ProblemException(
        422,
        "publish-checks",
        "Integração inválida",
        "A integração não passou nas conferências",
        violations);
  }
}
