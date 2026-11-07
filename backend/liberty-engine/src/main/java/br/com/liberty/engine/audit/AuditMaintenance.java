package br.com.liberty.engine.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Manutenção diária das partições mensais da auditoria: garante as dos próximos meses e arquiva em
 * tabelas próprias as anteriores à retenção. A rotina roda no banco com os direitos do dono (a
 * aplicação não tem DDL) e serializa instâncias concorrentes com um bloqueio de aplicação.
 */
public final class AuditMaintenance {

  private static final Logger LOG = LoggerFactory.getLogger(AuditMaintenance.class);

  private final JdbcTemplate jdbc;
  private final LibertyAuditProperties properties;

  public AuditMaintenance(JdbcTemplate jdbc, LibertyAuditProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
  public void run() {
    try {
      jdbc.update(
          "EXEC dbo.sp_int_audit_maintain @monthsAhead = ?, @retentionMonths = ?",
          properties.monthsAhead(),
          properties.retentionMonths());
      LOG.info("Manutenção das partições de auditoria concluída");
    } catch (RuntimeException e) {
      LOG.error("Falha na manutenção das partições de auditoria", e);
    }
  }
}
