package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.tenancy.TenantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/** Papel de um usuário (claim `sub` do OIDC) em um tenant. */
@Entity
@Table(name = "int_user_role")
public class UserRoleEntity {

  @Embeddable
  public record Key(
      @Column(name = "user_sub") String userSub,
      @Column(name = "tenant_id") Long tenantId,
      @Enumerated(EnumType.STRING) @Column(name = "role") TenantRole role)
      implements Serializable {}

  @EmbeddedId private Key id;

  @Column(name = "created_at")
  private Instant createdAt;

  protected UserRoleEntity() {}

  public UserRoleEntity(String userSub, Long tenantId, TenantRole role) {
    this.id = new Key(userSub, tenantId, role);
    this.createdAt = Instant.now();
  }

  public Key getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
