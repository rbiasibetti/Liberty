package br.com.liberty.snapshot;

import tools.jackson.databind.node.ObjectNode;

/**
 * Passo de conversão de um snapshot da versão {@code from()} para {@code from() + 1}. Roda sobre a
 * árvore JSON, antes da validação, para versões publicadas há meses continuarem executando depois
 * que o formato evoluir. Um passo nunca muda o significado do documento; só a forma.
 */
public interface Migration {

  int from();

  /** Converte no lugar e devolve o próprio objeto (a versão é atualizada pelo conversor). */
  ObjectNode apply(ObjectNode document);
}
