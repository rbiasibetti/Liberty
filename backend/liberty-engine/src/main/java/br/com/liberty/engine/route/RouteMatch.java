package br.com.liberty.engine.route;

import java.util.Map;

/** Rota encontrada e os valores das variáveis do caminho (ainda como texto). */
public record RouteMatch(PublishedIntegration integration, Map<String, String> pathVariables) {}
