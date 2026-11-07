package br.com.liberty.snapshot;

import br.com.liberty.snapshot.model.Snapshot;
import br.com.liberty.snapshot.validation.BodySchemaChecker;
import br.com.liberty.snapshot.validation.FieldError;
import br.com.liberty.snapshot.validation.SchemaValidator;
import br.com.liberty.snapshot.validation.SemanticValidator;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Porta de entrada do snapshot: confere o tamanho, lê o JSON sem chaves duplicadas, converte para a
 * versão corrente, valida a estrutura e a semântica (um erro por campo) e produz o hash da forma
 * canônica.
 */
public final class SnapshotService {

  public static final int CURRENT_VERSION = 1;
  public static final int MAX_BYTES = 1024 * 1024;
  private static final int MAX_ERRORS = 100;

  private final JsonMapper mapper =
      JsonMapper.builder(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(MAX_BYTES)
                          .maxNumberLength(50)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();
  private final SchemaValidator schemaValidator =
      new SchemaValidator("/snapshot/snapshot-v1.schema.json");
  private final SemanticValidator semanticValidator =
      new SemanticValidator(new BodySchemaChecker());
  private final SnapshotConverter converter;

  public SnapshotService() {
    this(new SnapshotConverter(CURRENT_VERSION, List.of()));
  }

  public SnapshotService(SnapshotConverter converter) {
    this.converter = converter;
  }

  /**
   * Lê, converte, valida e calcula o hash; lança {@link SnapshotValidationException} com os erros
   * por campo.
   */
  public ParsedSnapshot parse(byte[] json) {
    if (json == null || json.length == 0) {
      throw error("", "empty", "Snapshot vazio");
    }
    if (json.length > MAX_BYTES) {
      throw error("", "too_large", "Snapshot maior que " + MAX_BYTES + " bytes");
    }
    JsonNode raw;
    try {
      raw = mapper.readTree(json);
    } catch (JacksonException e) {
      throw error("", "invalid_json", "JSON inválido ou com chaves duplicadas");
    }
    int originalVersion = SnapshotConverter.versionOf(raw);
    JsonNode document = converter.toLatest(raw);
    List<FieldError> errors = validate(document);
    if (!errors.isEmpty()) {
      throw new SnapshotValidationException(errors);
    }
    Snapshot model = mapper.treeToValue(document, Snapshot.class);
    return new ParsedSnapshot(
        document,
        model,
        originalVersion,
        CanonicalJson.write(document),
        CanonicalJson.sha256(document));
  }

  /**
   * Erros do documento já na versão corrente: estrutura primeiro; a semântica só se a estrutura
   * estiver sã.
   */
  public List<FieldError> validate(JsonNode document) {
    List<FieldError> errors = new ArrayList<>(schemaValidator.validate(document));
    if (errors.isEmpty()) {
      errors.addAll(semanticValidator.validate(document));
    }
    return errors.stream().distinct().sorted().limit(MAX_ERRORS).toList();
  }

  private static SnapshotValidationException error(String path, String code, String message) {
    return new SnapshotValidationException(List.of(new FieldError(path, code, message)));
  }
}
