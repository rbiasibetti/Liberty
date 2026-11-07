package br.com.liberty.snapshot.model;

import java.util.List;

/**
 * Snapshot de integração na versão 1: o que o motor executa, sempre um documento inteiro e
 * imutável.
 */
public record Snapshot(
    int schemaVersion,
    Integration integration,
    Request request,
    Operation operation,
    Scripts scripts,
    List<NamedQuery> namedQueries,
    Response response,
    Idempotency idempotency,
    List<TestCase> tests) {

  public Snapshot {
    request = request == null ? new Request(List.of(), null, null) : request;
    scripts = scripts == null ? new Scripts(null, null) : scripts;
    namedQueries = namedQueries == null ? List.of() : List.copyOf(namedQueries);
    response = response == null ? new Response(null) : response;
    idempotency = idempotency == null ? new Idempotency(false) : idempotency;
    tests = tests == null ? List.of() : List.copyOf(tests);
  }
}
