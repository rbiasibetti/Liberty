package br.com.liberty.infrastructure.tenancy;

import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantScope;
import org.springframework.core.task.TaskDecorator;

/**
 * Leva o tenant da thread que submete a tarefa para a thread que a executa e limpa o contexto ao
 * fim; tarefa submetida sem tenant executa sem tenant, nunca com o de uma execução anterior.
 */
public final class TenantContextTaskDecorator implements TaskDecorator {

  @Override
  public Runnable decorate(Runnable task) {
    TenantScope captured = TenantContext.current().orElse(null);
    return () -> {
      if (captured == null) {
        TenantContext.runWithoutTenant(task);
      } else {
        TenantContext.runAs(captured, task);
      }
    };
  }
}
