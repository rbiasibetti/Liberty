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
 * Mapeia int_tenant_connection. Gerada a partir do schema V1; o Hibernate valida as colunas na
 * subida.
 */
@Entity
@Table(name = "int_tenant_connection")
public class TenantConnectionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "environment")
  private EnvironmentKind environment;

  @Column(name = "jdbc_url")
  private String jdbcUrl;

  @Column(name = "db_username")
  private String dbUsername;

  @Column(name = "secret_ref")
  private String secretRef;

  @Column(name = "pool_max")
  private Integer poolMax;

  @Column(name = "status")
  private String status;

  @Column(name = "created_at")
  private Instant createdAt;

  protected TenantConnectionEntity() {}

  public TenantConnectionEntity(
      Long tenantId,
      EnvironmentKind environment,
      String jdbcUrl,
      String dbUsername,
      String secretRef,
      Integer poolMax) {
    this.tenantId = tenantId;
    this.environment = environment;
    this.jdbcUrl = jdbcUrl;
    this.dbUsername = dbUsername;
    this.secretRef = secretRef;
    this.poolMax = poolMax;
    this.createdAt = Instant.now();
    this.status = "ACTIVE";
  }

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public EnvironmentKind getEnvironment() {
    return environment;
  }

  public String getJdbcUrl() {
    return jdbcUrl;
  }

  public String getDbUsername() {
    return dbUsername;
  }

  public String getSecretRef() {
    return secretRef;
  }

  public Integer getPoolMax() {
    return poolMax;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setPoolMax(Integer poolMax) {
    this.poolMax = poolMax;
  }

  public void setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
  }

  public void setSecretRef(String secretRef) {
    this.secretRef = secretRef;
  }

  public void setDbUsername(String dbUsername) {
    this.dbUsername = dbUsername;
  }
}
