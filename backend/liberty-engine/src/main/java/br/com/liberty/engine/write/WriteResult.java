package br.com.liberty.engine.write;

import tools.jackson.databind.JsonNode;

/** Resposta de uma escrita; {@code replayed} indica resposta repetida de uma chave já concluída. */
public record WriteResult(int status, JsonNode body, boolean replayed) {}
