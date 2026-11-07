package br.com.liberty.engine.route;

import br.com.liberty.snapshot.model.Snapshot;

/** Integração servida: identidade, versão publicada e o snapshot já validado. */
public record PublishedIntegration(
    long integrationId,
    String key,
    String method,
    long versionId,
    int versionNo,
    Snapshot snapshot,
    RoutePattern pattern) {}
