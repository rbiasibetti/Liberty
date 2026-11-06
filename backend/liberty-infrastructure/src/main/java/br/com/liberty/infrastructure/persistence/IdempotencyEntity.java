package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

/** Registro de idempotência de uma escrita; guardado por 24 horas. */
@Entity
@Table(name = "int_idempotency")
public class IdempotencyEntity {

  @Embeddable
  public record Key(
      @Column(name = "tenant_id") Long tenantId,
      @Column(name = "integration_id") Long integrationId,
      @Column(name = "idem_key") String idemKey)
      implements Serializable {}

  @EmbeddedId private Key id;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "request_sha256", length = 32)
  private byte[] requestSha256;

  @Column(name = "status")
  private String status;

  @Column(name = "http_status")
  private Short httpStatus;

  @Nationalized
  @Column(name = "response", length = Length.LONG32)
  private String response;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  protected IdempotencyEntity() {}

  public IdempotencyEntity(Key id, byte[] requestSha256, Instant expiresAt) {
    this.id = id;
    this.requestSha256 = requestSha256;
    this.status = "IN_PROGRESS";
    this.createdAt = Instant.now();
    this.expiresAt = expiresAt;
  }

  public void complete(short httpStatus, String response) {
    this.status = "DONE";
    this.httpStatus = httpStatus;
    this.response = response;
  }

  public Key getId() {
    return id;
  }

  public byte[] getRequestSha256() {
    return requestSha256;
  }

  public String getStatus() {
    return status;
  }

  public Short getHttpStatus() {
    return httpStatus;
  }

  public String getResponse() {
    return response;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
