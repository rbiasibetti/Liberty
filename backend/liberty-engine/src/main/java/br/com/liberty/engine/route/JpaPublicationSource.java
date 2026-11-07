package br.com.liberty.engine.route;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.infrastructure.persistence.PublicationRepository;
import br.com.liberty.infrastructure.persistence.PublishedRow;
import br.com.liberty.infrastructure.persistence.TenantEntity;
import br.com.liberty.infrastructure.persistence.TenantRepository;
import br.com.liberty.snapshot.ParsedSnapshot;
import br.com.liberty.snapshot.SnapshotService;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lê do core as publicações ativas do tenant e ambiente, validando de novo cada snapshot antes de
 * servi-lo.
 */
@Component
class JpaPublicationSource implements PublicationSource {

  private static final Logger LOG = LoggerFactory.getLogger(JpaPublicationSource.class);
  private static final String NONE = "none";

  private final TenantRepository tenants;
  private final PublicationRepository publications;
  private final SnapshotService snapshots;

  JpaPublicationSource(
      TenantRepository tenants, PublicationRepository publications, SnapshotService snapshots) {
    this.tenants = tenants;
    this.publications = publications;
    this.snapshots = snapshots;
  }

  @Override
  @Transactional(readOnly = true)
  public Loaded load(TenantScope scope) {
    Optional<TenantEntity> tenant = activeTenant(scope);
    if (tenant.isEmpty()) {
      return new Loaded(NONE, RouteTable.empty());
    }
    Long tenantId = tenant.get().getId();
    String token = publications.servedToken(tenantId, scope.environment());
    List<PublishedIntegration> served = new ArrayList<>();
    for (PublishedRow row : publications.findServed(tenantId, scope.environment())) {
      toIntegration(scope, row).ifPresent(served::add);
    }
    return new Loaded(token, RouteTable.of(served));
  }

  @Override
  @Transactional(readOnly = true)
  public String token(TenantScope scope) {
    return activeTenant(scope)
        .map(t -> publications.servedToken(t.getId(), scope.environment()))
        .orElse(NONE);
  }

  private Optional<TenantEntity> activeTenant(TenantScope scope) {
    return tenants.findBySlug(scope.tenant().value()).filter(t -> "ACTIVE".equals(t.getStatus()));
  }

  private Optional<PublishedIntegration> toIntegration(TenantScope scope, PublishedRow row) {
    try {
      ParsedSnapshot parsed = snapshots.parse(row.snapshot().getBytes(StandardCharsets.UTF_8));
      var integration = parsed.model().integration();
      if (!integration.key().equals(row.integrationKey())
          || !integration.method().equals(row.httpMethod())
          || !integration.route().equals(row.routePattern())) {
        LOG.error(
            "Snapshot da integração {} ({}) não confere com o cadastro; não será servida",
            row.integrationKey(),
            scope);
        return Optional.empty();
      }
      return Optional.of(
          new PublishedIntegration(
              row.integrationId(),
              row.integrationKey(),
              row.httpMethod(),
              row.versionId(),
              row.versionNo(),
              parsed.model(),
              RoutePattern.parse(row.routePattern())));
    } catch (SnapshotValidationException | IllegalArgumentException e) {
      LOG.error(
          "Snapshot inválido na integração {} ({}); não será servida: {}",
          row.integrationKey(),
          scope,
          e.getMessage());
      return Optional.empty();
    }
  }
}
