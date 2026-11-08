package br.com.liberty.engine.exports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * Armazenamento em disco: escreve num temporário e só renomeia no commit; recusa chaves que escapem
 * da pasta.
 */
public final class LocalObjectStore implements ObjectStore {

  private static final Pattern KEY = Pattern.compile("[0-9]{1,18}/[0-9]{1,18}\\.[a-z]{2,5}");

  private final Path root;

  public LocalObjectStore(Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root);
  }

  private Path path(String key) {
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Chave de objeto inválida");
    }
    Path resolved = root.resolve(key).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Chave de objeto inválida");
    }
    return resolved;
  }

  @Override
  public Upload begin(String key) throws IOException {
    Path target = path(key);
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".parcial-", ".tmp");
    OutputStream out = Files.newOutputStream(temp);
    return new Upload() {
      private boolean done;

      @Override
      public OutputStream stream() {
        return out;
      }

      @Override
      public void commit() throws IOException {
        out.close();
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        done = true;
      }

      @Override
      public void close() throws IOException {
        if (!done) {
          try {
            out.close();
          } finally {
            Files.deleteIfExists(temp);
          }
        }
      }
    };
  }

  @Override
  public InputStream open(String key) throws IOException {
    return Files.newInputStream(path(key));
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(path(key));
  }
}
