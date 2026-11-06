package br.com.liberty.domain.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Tenant da thread atual. Só o filtro de autenticação (a partir do JWT) e o decorador de tarefas
 * escrevem aqui; quem precisa do tenant chama {@link #require()}, que falha se não houver. Nunca há
 * tenant padrão.
 */
public final class TenantContext {

  private static final ThreadLocal<TenantScope> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Optional<TenantScope> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static TenantScope require() {
    TenantScope scope = CURRENT.get();
    if (scope == null) {
      throw new IllegalStateException("Nenhum tenant no contexto da execução");
    }
    return scope;
  }

  public static void set(TenantScope scope) {
    CURRENT.set(Objects.requireNonNull(scope, "scope"));
  }

  public static void clear() {
    CURRENT.remove();
  }

  /** Executa com o tenant definido e restaura o contexto anterior ao fim, mesmo com exceção. */
  public static <T> T callAs(TenantScope scope, Callable<T> action) throws Exception {
    TenantScope previous = CURRENT.get();
    set(scope);
    try {
      return action.call();
    } finally {
      restore(previous);
    }
  }

  public static <T> T supplyAs(TenantScope scope, Supplier<T> action) {
    TenantScope previous = CURRENT.get();
    set(scope);
    try {
      return action.get();
    } finally {
      restore(previous);
    }
  }

  public static void runAs(TenantScope scope, Runnable action) {
    supplyAs(
        scope,
        () -> {
          action.run();
          return null;
        });
  }

  /** Executa sem tenant (limpa o contexto) e restaura o anterior; usado por tarefas sem escopo. */
  public static void runWithoutTenant(Runnable action) {
    TenantScope previous = CURRENT.get();
    clear();
    try {
      action.run();
    } finally {
      restore(previous);
    }
  }

  private static void restore(TenantScope previous) {
    if (previous == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(previous);
    }
  }
}
