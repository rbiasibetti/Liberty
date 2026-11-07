package br.com.liberty.engine.audit;

import java.time.Instant;

/**
 * Fato auditável de uma execução: só metadados (nunca corpo, parâmetros nem valores). O tenant vai
 * pelo slug; o identificador numérico é resolvido na gravação, fora do caminho da requisição.
 */
public record AuditEvent(
    Instant createdAt,
    String tenantSlug,
    String integrationKey,
    Long integrationId,
    Long versionId,
    String environment,
    String httpMethod,
    String route,
    int statusCode,
    long durationMs,
    Long dbDurationMs,
    Long scriptDurationMs,
    String errorCode,
    String errorMessage,
    String requestId,
    String clientId) {}
