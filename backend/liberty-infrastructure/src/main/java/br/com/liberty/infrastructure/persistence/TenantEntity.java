package br.com.liberty.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Nationalized;

/** Mapeia int_tenant. Gerada a partir do schema V1; o Hibernate valida as colunas na subida. */
@Entity
@Table(name = "int_tenant")
public class TenantEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "slug")
  private String slug;

  @Nationalized
  @Column(name = "name")
  private String name;

  @Column(name = "plan_code")
  private String planCode;

  @Column(name = "status")
  private String status;

  @Column(name = "created_at")
  private Instant createdAt;

  protected TenantEntity() {}

  public TenantEntity(String slug, String name, String planCode) {
    this.slug = slug;
    this.name = name;
    this.planCode = planCode;
    this.createdAt = Instant.now();
    this.status = "ACTIVE";
  }

  public Long getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public String getName() {
    return name;
  }

  public String getPlanCode() {
    return planCode;
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

  public void setPlanCode(String planCode) {
    this.planCode = planCode;
  }

  public void setName(String name) {
    this.name = name;
  }
}
