package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/** Host que os scripts de um tenant podem chamar com {@code http.fetch}. */
@Entity
@Table(name = "int_tenant_http_host")
public class TenantHttpHostEntity {

  @Embeddable
  public record Key(@Column(name = "tenant_id") Long tenantId, @Column(name = "host") String host)
      implements Serializable {}

  @EmbeddedId private Key id;

  @Column(name = "created_by")
  private String createdBy;

  @Column(name = "created_at")
  private Instant createdAt;

  protected TenantHttpHostEntity() {}

  public TenantHttpHostEntity(Long tenantId, String host, String createdBy) {
    this.id = new Key(tenantId, host);
    this.createdBy = createdBy;
    this.createdAt = Instant.now();
  }

  public Key getId() {
    return id;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
