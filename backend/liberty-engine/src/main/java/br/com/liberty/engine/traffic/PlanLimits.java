package br.com.liberty.engine.traffic;

/** Limites efetivos de um tenant: taxa sustentada, rajada e execuções simultâneas. */
public record PlanLimits(int ratePerSecond, int burst, int maxConcurrent) {

  public PlanLimits {
    ratePerSecond = Math.max(1, ratePerSecond);
    burst = Math.max(ratePerSecond, burst);
    maxConcurrent = Math.max(1, maxConcurrent);
  }
}
