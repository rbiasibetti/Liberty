package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Nationalized;

/**
 * Linha de auditoria, somente leitura pela JPA: a gravação em lote é feita por JDBC (E17), então a
 * entidade serve às telas de consulta.
 */
@Entity
@Immutable
@Table(name = "int_audit_execution")
public class AuditExecutionEntity {

  @Id
  @Column(name = "id")
  private Long id;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "integration_key")
  private String integrationKey;

  @Column(name = "integration_id")
  private Long integrationId;

  @Column(name = "version_id")
  private Long versionId;

  @Column(name = "environment")
  private String environment;

  @Column(name = "http_method")
  private String httpMethod;

  @Column(name = "route")
  private String route;

  @Column(name = "status_code")
  private Short statusCode;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @Column(name = "db_duration_ms")
  private Integer dbDurationMs;

  @Column(name = "script_duration_ms")
  private Integer scriptDurationMs;

  @Column(name = "error_code")
  private String errorCode;

  @Nationalized
  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "request_id")
  private String requestId;

  @Column(name = "client_id")
  private String clientId;

  protected AuditExecutionEntity() {}

  public Long getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getIntegrationKey() {
    return integrationKey;
  }

  public Long getIntegrationId() {
    return integrationId;
  }

  public Long getVersionId() {
    return versionId;
  }

  public String getEnvironment() {
    return environment;
  }

  public String getHttpMethod() {
    return httpMethod;
  }

  public String getRoute() {
    return route;
  }

  public Short getStatusCode() {
    return statusCode;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public Integer getDbDurationMs() {
    return dbDurationMs;
  }

  public Integer getScriptDurationMs() {
    return scriptDurationMs;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getClientId() {
    return clientId;
  }
}
