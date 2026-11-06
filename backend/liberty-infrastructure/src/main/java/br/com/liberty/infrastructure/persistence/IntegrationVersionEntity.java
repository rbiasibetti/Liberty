package br.com.liberty.infrastructure.persistence;

import br.com.liberty.domain.integration.VersionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

/**
 * Mapeia int_integration_version. Gerada a partir do schema V1; o Hibernate valida as colunas na
 * subida.
 */
@Entity
@Table(name = "int_integration_version")
public class IntegrationVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "integration_id")
  private Long integrationId;

  @Column(name = "version_no")
  private Integer versionNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "state")
  private VersionState state;

  @Nationalized
  @Column(name = "snapshot", length = Length.LONG32)
  private String snapshot;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "snapshot_sha256", length = 32)
  private byte[] snapshotSha256;

  @Column(name = "author_sub")
  private String authorSub;

  @Column(name = "reviewer_sub")
  private String reviewerSub;

  @Nationalized
  @Column(name = "review_comment")
  private String reviewComment;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  protected IntegrationVersionEntity() {}

  public IntegrationVersionEntity(
      Long tenantId,
      Long integrationId,
      Integer versionNo,
      VersionState state,
      String snapshot,
      byte[] snapshotSha256,
      String authorSub) {
    this.tenantId = tenantId;
    this.integrationId = integrationId;
    this.versionNo = versionNo;
    this.state = state;
    this.snapshot = snapshot;
    this.snapshotSha256 = snapshotSha256;
    this.authorSub = authorSub;
    this.createdAt = Instant.now();
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

  public Integer getVersionNo() {
    return versionNo;
  }

  public VersionState getState() {
    return state;
  }

  public String getSnapshot() {
    return snapshot;
  }

  public byte[] getSnapshotSha256() {
    return snapshotSha256;
  }

  public String getAuthorSub() {
    return authorSub;
  }

  public String getReviewerSub() {
    return reviewerSub;
  }

  public String getReviewComment() {
    return reviewComment;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getReviewedAt() {
    return reviewedAt;
  }

  public void setState(VersionState state) {
    this.state = state;
  }

  public void setSnapshot(String snapshot) {
    this.snapshot = snapshot;
  }

  public void setSnapshotSha256(byte[] snapshotSha256) {
    this.snapshotSha256 = snapshotSha256;
  }

  public void setReviewerSub(String reviewerSub) {
    this.reviewerSub = reviewerSub;
  }

  public void setReviewComment(String reviewComment) {
    this.reviewComment = reviewComment;
  }

  public void setReviewedAt(Instant reviewedAt) {
    this.reviewedAt = reviewedAt;
  }
}
