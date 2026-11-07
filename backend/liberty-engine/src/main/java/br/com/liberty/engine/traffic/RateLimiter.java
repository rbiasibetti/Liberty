package br.com.liberty.engine.traffic;

/** Limite de taxa por tenant (balde de fichas). */
public interface RateLimiter {

  /** Resultado de uma tentativa: se passou, o teto, o que sobra e quando tentar de novo. */
  record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {}

  Decision tryAcquire(String tenant, PlanLimits limits);
}
