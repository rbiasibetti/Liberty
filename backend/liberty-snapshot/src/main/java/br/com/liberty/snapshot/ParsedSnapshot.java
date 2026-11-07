package br.com.liberty.snapshot;

import br.com.liberty.snapshot.model.Snapshot;
import tools.jackson.databind.JsonNode;

/** Snapshot validado: documento na versão corrente, modelo tipado, forma canônica e hash. */
public record ParsedSnapshot(
    JsonNode document, Snapshot model, int originalVersion, String canonicalJson, byte[] sha256) {

  public String sha256Hex() {
    return java.util.HexFormat.of().formatHex(sha256);
  }
}
