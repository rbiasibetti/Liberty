package br.com.liberty.infrastructure.security;

import java.util.List;

/** Armazenamento compartilhado entre instâncias das chaves de assinatura (o cofre, em produção). */
public interface SigningKeyStore {

  /** Chaves e a versão do documento, usada na gravação condicional (0 = ainda não existe). */
  record Snapshot(List<SigningKey> keys, long version) {}

  Snapshot load();

  /**
   * Grava só se ninguém gravou desde {@code expectedVersion}; devolve false se perdeu a corrida.
   */
  boolean save(List<SigningKey> keys, long expectedVersion);
}
