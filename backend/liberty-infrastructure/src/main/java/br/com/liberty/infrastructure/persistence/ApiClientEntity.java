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
import org.hibernate.annotations.Nationalized;

/** Mapeia int_api_client. Gerada a partir do schema V1; o Hibernate valida as colunas na subida. */
@Entity
@Table(name = "int_api_client")
public class ApiClientEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "client_id")
  private String clientId;

  @Column(name = "secret_hash")
  private String secretHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "environment")
  private EnvironmentKind environment;

  @Nationalized
  @Column(name = "scopes")
  private String scopes;

  @Column(name = "status")
  private String status;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected ApiClientEntity() {}

  public ApiClientEntity(
      Long tenantId,
      String clientId,
      String secretHash,
      EnvironmentKind environment,
      String scopes) {
    this.tenantId = tenantId;
    this.clientId = clientId;
    this.secretHash = secretHash;
    this.environment = environment;
    this.scopes = scopes;
    this.createdAt = Instant.now();
    this.status = "ACTIVE";
  }

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getClientId() {
    return clientId;
  }

  public String getSecretHash() {
    return secretHash;
  }

  public EnvironmentKind getEnvironment() {
    return environment;
  }

  public String getScopes() {
    return scopes;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
