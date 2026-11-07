package br.com.liberty.engine.write;

import java.util.List;
import java.util.Map;

/**
 * O que o motor de escrita recebe do canal HTTP: corpo bruto, parâmetros e chave de idempotência.
 */
public record WriteRequest(
    byte[] body,
    Map<String, String> pathVariables,
    Map<String, List<String>> query,
    String idempotencyKey) {}
