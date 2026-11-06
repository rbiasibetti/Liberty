package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Mapeia int_publication. Gerada a partir do schema V1; o Hibernate valida as colunas na subida.
 */
@Entity
@Table(name = "int_publication")
public class PublicationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "integration_id")
  private Long integrationId;

  @Column(name = "version_id")
  private Long versionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "environment")
  private EnvironmentKind environment;

  @Column(name = "is_active")
  private boolean active;

  @Column(name = "published_by")
  private String publishedBy;

  @Column(name = "published_at")
  private Instant publishedAt;

  protected PublicationEntity() {}

  public PublicationEntity(
      Long tenantId,
      Long integrationId,
      Long versionId,
      EnvironmentKind environment,
      boolean active,
      String publishedBy) {
    this.tenantId = tenantId;
    this.integrationId = integrationId;
    this.versionId = versionId;
    this.environment = environment;
    this.active = active;
    this.publishedBy = publishedBy;
    this.publishedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public Long getIntegrationId() {
    return integrationId;
  }

  public Long getVersionId() {
    return versionId;
  }

  public EnvironmentKind getEnvironment() {
    return environment;
  }

  public boolean isActive() {
    return active;
  }

  public String getPublishedBy() {
    return publishedBy;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
