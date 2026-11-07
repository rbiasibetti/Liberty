package br.com.liberty.snapshot;

import br.com.liberty.snapshot.validation.FieldError;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Leva um snapshot de qualquer versão suportada até a versão corrente, passo a passo. */
public final class SnapshotConverter {

  private final int latest;
  private final Map<Integer, Migration> steps;

  public SnapshotConverter(int latest, List<Migration> migrations) {
    this.latest = latest;
    this.steps =
        migrations.stream().collect(Collectors.toMap(Migration::from, Function.identity()));
    for (int version = 1; version < latest; version++) {
      if (!steps.containsKey(version)) {
        throw new IllegalArgumentException(
            "Falta a conversão da versão " + version + " para " + (version + 1));
      }
    }
  }

  public int latest() {
    return latest;
  }

  /** Versão declarada no documento, ou 0 se ausente ou inválida. */
  public static int versionOf(JsonNode document) {
    JsonNode version = document.path("schemaVersion");
    return version.isInt() ? version.asInt() : 0;
  }

  public JsonNode toLatest(JsonNode document) {
    if (!(document instanceof ObjectNode root)) {
      throw new SnapshotValidationException(
          List.of(new FieldError("", "not_an_object", "O snapshot deve ser um objeto JSON")));
    }
    int version = versionOf(root);
    if (version < 1) {
      throw new SnapshotValidationException(
          List.of(
              new FieldError(
                  "/schemaVersion", "invalid_version", "schemaVersion ausente ou inválido")));
    }
    if (version > latest) {
      throw new SnapshotValidationException(
          List.of(
              new FieldError(
                  "/schemaVersion",
                  "future_version",
                  "Versão " + version + " é mais nova que a suportada (" + latest + ")")));
    }
    ObjectNode current = root.deepCopy();
    while (versionOf(current) < latest) {
      int from = versionOf(current);
      current = steps.get(from).apply(current);
      current.put("schemaVersion", from + 1);
    }
    return current;
  }
}
