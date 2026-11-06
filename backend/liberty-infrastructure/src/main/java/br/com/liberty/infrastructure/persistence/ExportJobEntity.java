package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.hibernate.annotations.Nationalized;

/** Mapeia int_export_job. Gerada a partir do schema V1; o Hibernate valida as colunas na subida. */
@Entity
@Table(name = "int_export_job")
public class ExportJobEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "requested_by")
  private String requestedBy;

  @Column(name = "status")
  private String status;

  @Column(name = "format")
  private String format;

  @Nationalized
  @Column(name = "filter_json", length = Length.LONG32)
  private String filterJson;

  @Column(name = "object_key")
  private String objectKey;

  @Nationalized
  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  protected ExportJobEntity() {}

  public ExportJobEntity(
      Long tenantId, String requestedBy, String status, String format, String filterJson) {
    this.tenantId = tenantId;
    this.requestedBy = requestedBy;
    this.status = status;
    this.format = format;
    this.filterJson = filterJson;
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Long getTenantId() {
    return tenantId;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getStatus() {
    return status;
  }

  public String getFormat() {
    return format;
  }

  public String getFilterJson() {
    return filterJson;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setObjectKey(String objectKey) {
    this.objectKey = objectKey;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
