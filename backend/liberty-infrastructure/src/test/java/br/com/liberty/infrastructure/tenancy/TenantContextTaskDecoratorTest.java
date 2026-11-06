package br.com.liberty.infrastructure.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import br.com.liberty.domain.tenancy.TenantContext;
import br.com.liberty.domain.tenancy.TenantId;
import br.com.liberty.domain.tenancy.TenantScope;
import java.util.Optional;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class TenantContextTaskDecoratorTest {

  private static final TenantScope A =
      new TenantScope(new TenantId("cliente-a"), EnvironmentKind.HML);
  private static final TenantScope B =
      new TenantScope(new TenantId("cliente-b"), EnvironmentKind.PRD);

  @AfterEach
  void clean() {
    TenantContext.clear();
  }

  private static ThreadPoolTaskExecutor singleThreadExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setTaskDecorator(new TenantContextTaskDecorator());
    executor.initialize();
    return executor;
  }

  @Test
  void tenantTravelsToTheWorkerThreadAndIsClearedAfterwards() throws Exception {
    ThreadPoolTaskExecutor executor = singleThreadExecutor();
    try {
      TenantContext.set(A);
      Future<Optional<TenantScope>> withA = executor.submit(TenantContext::current);
      TenantContext.set(B);
      Future<Optional<TenantScope>> withB = executor.submit(TenantContext::current);
      TenantContext.clear();
      Future<Optional<TenantScope>> without = executor.submit(TenantContext::current);

      assertThat(withA.get()).contains(A);
      assertThat(withB.get()).contains(B);
      assertThat(without.get()).isEmpty(); // a mesma thread, sem herdar o tenant da tarefa anterior
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void aTaskThatSetsTheTenantItselfDoesNotLeakItToTheNextOne() throws Exception {
    ThreadPoolTaskExecutor executor = singleThreadExecutor();
    try {
      executor.submit(() -> TenantContext.set(B)).get();

      assertThat(executor.submit(TenantContext::current).get()).isEmpty();
    } finally {
      executor.shutdown();
    }
  }
}
