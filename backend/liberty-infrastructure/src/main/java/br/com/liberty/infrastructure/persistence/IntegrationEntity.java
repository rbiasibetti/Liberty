package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;

/**
 * Mapeia int_integration. Gerada a partir do schema V1; o Hibernate valida as colunas na subida.
 */
@Entity
@Table(name = "int_integration")
public class IntegrationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "integration_key")
  private String integrationKey;

  @Nationalized
  @Column(name = "name")
  private String name;

  @Column(name = "http_method")
  private String httpMethod;

  @Column(name = "route_pattern")
  private String routePattern;

  @Column(name = "archived")
  private boolean archived;

  @Column(name = "created_at")
  private Instant createdAt;

  protected IntegrationEntity() {}

  public IntegrationEntity(
      Long tenantId, String integrationKey, String name, String httpMethod, String routePattern) {
    this.tenantId = tenantId;
    this.integrationKey = integrationKey;
    this.name = name;
    this.httpMethod = httpMethod;
    this.routePattern = routePattern;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getIntegrationKey() {
    return integrationKey;
  }

  public String getName() {
    return name;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String getRoutePattern() {
    return routePattern;
  }

  public boolean isArchived() {
    return archived;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setArchived(boolean archived) {
    this.archived = archived;
  }

  public void setName(String name) {
    this.name = name;
  }
}
