package br.com.liberty.engine.traffic;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de tráfego por plano do tenant. Quem não tem plano configurado usa os valores padrão (50
 * requisições por segundo, rajada de 100, 20 execuções simultâneas; docs/requisitos). Com {@code
 * redis-uri} o limite de taxa vale somando todas as instâncias; sem ele, vale por instância.
 */
@ConfigurationProperties("liberty.limits")
public record LibertyLimitProperties(
    @DefaultValue("50") int ratePerSecond,
    @DefaultValue("100") int burst,
    @DefaultValue("20") int maxConcurrent,
    @DefaultValue("1m") Duration planCacheTtl,
    @DefaultValue("liberty:rl:") String redisKeyPrefix,
    String redisUri,
    Map<String, Plan> plans) {

  /** Limites de um plano; valor omitido herda o padrão. */
  public record Plan(Integer ratePerSecond, Integer burst, Integer maxConcurrent) {}

  public LibertyLimitProperties {
    plans = plans == null ? Map.of() : Map.copyOf(plans);
  }

  /** Limites efetivos do plano (ou os padrão, se o plano não está configurado). */
  public PlanLimits limits(String planCode) {
    Plan plan = plans.get(planCode);
    if (plan == null) {
      return new PlanLimits(ratePerSecond, burst, maxConcurrent);
    }
    return new PlanLimits(
        plan.ratePerSecond() == null ? ratePerSecond : plan.ratePerSecond(),
        plan.burst() == null ? burst : plan.burst(),
        plan.maxConcurrent() == null ? maxConcurrent : plan.maxConcurrent());
  }
}
