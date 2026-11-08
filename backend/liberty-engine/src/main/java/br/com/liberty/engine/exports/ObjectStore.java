package br.com.liberty.engine.exports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Armazenamento de arquivos gerados. A chave é {@code <tenantId>/<jobId>.<ext>}, escolhida pelo
 * motor, nunca pelo usuário. Hoje existe a implementação em disco; um adaptador S3-compatível entra
 * atrás desta interface sem mudar o resto.
 */
public interface ObjectStore {

  /**
   * Escrita em andamento: o objeto só existe depois de {@link #commit()}; fechar sem confirmar
   * descarta.
   */
  interface Upload extends AutoCloseable {
    OutputStream stream();

    void commit() throws IOException;

    @Override
    void close() throws IOException;
  }

  Upload begin(String key) throws IOException;

  InputStream open(String key) throws IOException;

  void delete(String key) throws IOException;
}
