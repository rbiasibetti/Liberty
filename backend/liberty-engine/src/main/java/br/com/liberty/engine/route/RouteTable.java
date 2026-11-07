package br.com.liberty.engine.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rotas servidas de um tenant e ambiente. Literal vence variável; rotas de mesma estrutura são
 * ambíguas.
 */
public final class RouteTable {

  private static final Logger LOG = LoggerFactory.getLogger(RouteTable.class);

  /** Resultado da busca: a rota, ou os métodos aceitos pelo caminho (405), ou nada (404). */
  public record Resolution(Optional<RouteMatch> match, Set<String> allowedMethods) {

    public boolean notFound() {
      return match.isEmpty() && allowedMethods.isEmpty();
    }
  }

  private static final Comparator<PublishedIntegration> MOST_SPECIFIC_FIRST =
      (a, b) -> {
        int[] x = a.pattern().specificity();
        int[] y = b.pattern().specificity();
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
          if (x[i] != y[i]) {
            return Integer.compare(x[i], y[i]);
          }
        }
        return Long.compare(a.integrationId(), b.integrationId());
      };

  private final List<PublishedIntegration> integrations;

  private RouteTable(List<PublishedIntegration> integrations) {
    this.integrations = integrations;
  }

  public static RouteTable empty() {
    return new RouteTable(List.of());
  }

  /**
   * Constrói a tabela; se duas integrações tiverem a mesma estrutura de rota e método, fica a de
   * menor id.
   */
  public static RouteTable of(List<PublishedIntegration> candidates) {
    Map<String, PublishedIntegration> bySignature = new HashMap<>();
    List<PublishedIntegration> sorted = new ArrayList<>(candidates);
    sorted.sort(Comparator.comparingLong(PublishedIntegration::integrationId));
    List<PublishedIntegration> kept = new ArrayList<>();
    for (PublishedIntegration integration : sorted) {
      String signature = integration.method() + " " + integration.pattern().signature();
      PublishedIntegration existing = bySignature.putIfAbsent(signature, integration);
      if (existing == null) {
        kept.add(integration);
      } else {
        LOG.error(
            "Rota ambígua {}: a integração {} não será servida (já existe {})",
            signature,
            integration.key(),
            existing.key());
      }
    }
    kept.sort(MOST_SPECIFIC_FIRST);
    return new RouteTable(List.copyOf(kept));
  }

  public int size() {
    return integrations.size();
  }

  public Resolution resolve(String method, List<String> path) {
    Set<String> allowed = new TreeSet<>();
    for (PublishedIntegration integration : integrations) {
      Optional<Map<String, String>> variables = integration.pattern().match(path);
      if (variables.isEmpty()) {
        continue;
      }
      if (integration.method().equals(method)) {
        return new Resolution(Optional.of(new RouteMatch(integration, variables.get())), Set.of());
      }
      allowed.add(integration.method());
    }
    return new Resolution(Optional.empty(), new LinkedHashSet<>(allowed));
  }
}
