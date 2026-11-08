package br.com.liberty.engine.queue;

import br.com.liberty.domain.tenancy.EnvironmentKind;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * O que um consumidor recebe: a identidade do tenant e do ambiente viaja com a mensagem, nunca é
 * deduzida do contexto do consumidor. {@code attempt} começa em 1.
 */
public record QueueEnvelope(
    long id,
    String tenant,
    EnvironmentKind environment,
    String queue,
    int attempt,
    String correlationId,
    Instant createdAt,
    JsonNode payload) {}
