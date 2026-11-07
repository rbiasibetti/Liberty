package br.com.liberty.infrastructure.security;

import java.util.List;

/**
 * Chaves em memória, só para desenvolvimento e testes (cada instância teria chaves diferentes). A
 * aplicação só usa isto com {@code liberty.security.keys.store=MEMORY}, explícito.
 */
public final class InMemorySigningKeyStore implements SigningKeyStore {

  private List<SigningKey> keys = List.of();
  private long version;

  @Override
  public synchronized Snapshot load() {
    return new Snapshot(keys, version);
  }

  @Override
  public synchronized boolean save(List<SigningKey> next, long expectedVersion) {
    if (expectedVersion != version) {
      return false;
    }
    keys = List.copyOf(next);
    version++;
    return true;
  }
}
