package br.com.liberty.snapshot;

import static br.com.liberty.snapshot.Fixtures.remove;
import static br.com.liberty.snapshot.Fixtures.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.snapshot.model.Node;
import br.com.liberty.snapshot.validation.FieldError;
import br.com.liberty.snapshot.validation.SnapshotValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

class SnapshotServiceTest {

  private static final String ROOT = "/operation/write/root";
  private final SnapshotService service = new SnapshotService();

  private List<FieldError> errorsOf(ObjectNode doc) {
    try {
      service.parse(Fixtures.bytes(doc));
      return List.of();
    } catch (SnapshotValidationException e) {
      return e.errors();
    }
  }

  // ---------- documentos válidos ----------

  @Test
  void parsesAMasterDetailInsertIntoTheTypedModel() {
    var parsed = service.parse(Fixtures.bytes(Fixtures.insert()));

    var model = parsed.model();
    assertThat(model.integration().key()).isEqualTo("pedido");
    assertThat(model.operation().type()).isEqualTo("INSERT");
    Node root = model.operation().write().root();
    assertThat(root.table()).isEqualTo("dbo.tb_pedido");
    assertThat(root.primaryKey()).isEqualTo("id");
    assertThat(root.fields())
        .extracting(f -> f.column())
        .containsExactly("nr_pedido", "nr_documento", "cd_loja", "origem");
    assertThat(root.fields().get(0).transforms())
        .extracting(t -> t.fn())
        .containsExactly("trim", "upper");
    assertThat(root.fields().get(2).param()).isEqualTo("loja");
    assertThat(root.fields().get(2).transforms().getFirst().args().getFirst().asString())
        .isEqualTo("MATRIZ");
    assertThat(root.fields().get(3).constant().asString()).isEqualTo("API");
    Node item = root.children().getFirst();
    assertThat(item.arrayPath()).isEqualTo("/itens");
    assertThat(item.foreignKey()).isEqualTo("pedido_id");
    assertThat(model.idempotency().required()).isTrue();
    assertThat(model.response().successStatus()).isEqualTo(201);
    assertThat(model.scripts().beforeExecute()).startsWith("function beforeExecute");
    assertThat(model.namedQueries())
        .singleElement()
        .satisfies(q -> assertThat(q.params()).containsExactly("doc"));
    assertThat(model.tests())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.expect().status()).isEqualTo(201);
              assertThat(t.request().params()).containsKey("loja");
            });
    assertThat(model.request().bodySchema().path("$defs").has("dinheiro")).isTrue();
  }

  @Test
  void deleteRemovesByKeyOnlyWithoutBodyChildrenOrNonKeyFields() {
    var parsed = service.parse(Fixtures.bytes(Fixtures.delete())).model();
    assertThat(parsed.operation().type()).isEqualTo("DELETE");
    assertThat(parsed.operation().write().root().keyColumns()).containsExactly("nr_documento");

    String root = "/operation/write/root";
    Map<String, ObjectNode> broken =
        Map.of(
            "delete_has_no_body",
            set(Fixtures.delete(), "/request/bodySchema", Map.of("type", "object")),
            "delete_no_children",
            set(
                Fixtures.delete(),
                root + "/children",
                List.of(
                    Map.of(
                        "table",
                        "dbo.t",
                        "arrayPath",
                        "/x",
                        "foreignKey",
                        "a",
                        "fields",
                        List.of(Map.of("column", "a", "source", "/a"))))),
            "delete_fields_from_params",
            set(
                Fixtures.delete(),
                root + "/fields/0",
                Map.of("column", "nr_documento", "source", "/x")),
            "delete_only_key_fields",
            set(
                Fixtures.delete(),
                root + "/fields",
                List.of(
                    Map.of("column", "nr_documento", "param", "documento"),
                    Map.of("column", "nome", "constant", "x"))),
            "method_operation_mismatch",
            set(Fixtures.delete(), "/integration/method", "POST"),
            "key_columns_required",
            remove(Fixtures.delete(), root + "/keyColumns"));
    broken.forEach(
        (code, doc) ->
            assertThat(errorsOf(doc)).as(code).extracting(FieldError::code).contains(code));
  }

  @Test
  void parsesSelectAndUpsertAndAppliesDefaults() {
    var select = service.parse(Fixtures.bytes(Fixtures.select())).model();
    var upsert = service.parse(Fixtures.bytes(Fixtures.upsert())).model();

    assertThat(select.operation().select().filters()).hasSize(2);
    assertThat(select.operation().select().pageSize().defaultSize()).isEqualTo(50);
    assertThat(select.operation().select().pageSize().max()).isEqualTo(500);
    assertThat(select.request().params().getFirst().isRequired())
        .as("parâmetro de caminho")
        .isTrue();
    assertThat(select.request().params().get(1).isRequired()).isFalse();
    assertThat(select.idempotency().required()).isFalse();
    assertThat(select.tests()).isEmpty();
    assertThat(select.scripts().beforeExecute()).isNull();
    assertThat(upsert.operation().write().root().keyColumns()).containsExactly("nr_documento");
  }

  @Test
  void hashIsTheSameForTheSameContentRegardlessOfKeyOrderAndFormatting() {
    byte[] pretty = Fixtures.bytes(Fixtures.insert());
    String reordered =
        "  \n{\"tests\":"
            + Fixtures.insert().get("tests")
            + ",\"idempotency\":{\"required\":true},"
            + "\"schemaVersion\":1,\"response\":{\"successStatus\":201},\"namedQueries\":"
            + Fixtures.insert().get("namedQueries")
            + ",\"scripts\":"
            + Fixtures.insert().get("scripts")
            + ",\"operation\":"
            + Fixtures.insert().get("operation")
            + ",\"request\":"
            + Fixtures.insert().get("request")
            + ",\"integration\":"
            + Fixtures.insert().get("integration")
            + "}\n";

    var a = service.parse(pretty);
    var b = service.parse(reordered.getBytes(StandardCharsets.UTF_8));

    assertThat(b.sha256Hex()).isEqualTo(a.sha256Hex());
    assertThat(b.canonicalJson()).isEqualTo(a.canonicalJson());
    assertThat(a.sha256()).hasSize(32);
  }

  @Test
  void anyContentChangeChangesTheHash() {
    String base = service.parse(Fixtures.bytes(Fixtures.insert())).sha256Hex();

    assertThat(
            service
                .parse(Fixtures.bytes(set(Fixtures.insert(), "/integration/name", "Outro nome")))
                .sha256Hex())
        .isNotEqualTo(base);
    assertThat(
            service
                .parse(Fixtures.bytes(set(Fixtures.insert(), "/idempotency/required", false)))
                .sha256Hex())
        .isNotEqualTo(base);
    assertThat(
            service
                .parse(Fixtures.bytes(set(Fixtures.insert(), ROOT + "/fields/3/constant", "OUTRA")))
                .sha256Hex())
        .isNotEqualTo(base);
  }

  @Test
  void parsingTwiceGivesTheSameHash() {
    assertThat(service.parse(Fixtures.bytes(Fixtures.select())).sha256Hex())
        .isEqualTo(service.parse(Fixtures.bytes(Fixtures.select())).sha256Hex());
  }

  // ---------- erro por campo ----------

  record Case(String name, ObjectNode doc, String path, String code) {}

  static List<Arguments> defects() {
    String bodyProps = "/request/bodySchema/properties";
    return List.of(
        // estrutura (JSON Schema)
        arg(
            "chave ausente",
            remove(Fixtures.insert(), "/integration/key"),
            "/integration/key",
            "required"),
        arg(
            "campo desconhecido na raiz",
            set(Fixtures.insert(), "/extra", 1),
            "/extra",
            "additionalProperties"),
        arg(
            "campo desconhecido aninhado",
            set(Fixtures.insert(), "/integration/dono", "x"),
            "/integration/dono",
            "additionalProperties"),
        arg(
            "método fora da lista",
            set(Fixtures.insert(), "/integration/method", "TRACE"),
            "/integration/method",
            "enum"),
        arg(
            "rota com maiúscula",
            set(Fixtures.insert(), "/integration/route", "/Pedidos"),
            "/integration/route",
            "pattern"),
        arg(
            "rota sem barra",
            set(Fixtures.insert(), "/integration/route", "pedidos"),
            "/integration/route",
            "pattern"),
        arg(
            "chave com maiúscula",
            set(Fixtures.insert(), "/integration/key", "Pedido"),
            "/integration/key",
            "pattern"),
        arg(
            "nome vazio",
            set(Fixtures.insert(), "/integration/name", ""),
            "/integration/name",
            "minLength"),
        arg(
            "tipo errado",
            set(Fixtures.insert(), "/idempotency/required", "sim"),
            "/idempotency/required",
            "type"),
        arg(
            "coluna com espaço",
            set(Fixtures.insert(), ROOT + "/fields/0/column", "nr pedido"),
            ROOT + "/fields/0/column",
            "pattern"),
        arg(
            "coluna com injeção",
            set(Fixtures.insert(), ROOT + "/fields/0/column", "x]; DROP TABLE t;--"),
            ROOT + "/fields/0/column",
            "pattern"),
        arg(
            "tabela com injeção",
            set(Fixtures.insert(), ROOT + "/table", "tb; DROP TABLE x"),
            ROOT + "/table",
            "pattern"),
        arg(
            "tabela com três partes",
            set(Fixtures.insert(), ROOT + "/table", "a.b.c"),
            ROOT + "/table",
            "pattern"),
        arg(
            "campo com duas origens",
            set(Fixtures.insert(), ROOT + "/fields/0/param", "loja"),
            ROOT + "/fields/0",
            "oneOf"),
        arg(
            "campo sem origem",
            remove(Fixtures.insert(), ROOT + "/fields/0/source"),
            ROOT + "/fields/0",
            "oneOf"),
        arg(
            "função desconhecida",
            set(Fixtures.insert(), ROOT + "/fields/0/transforms/0/fn", "eval"),
            ROOT + "/fields/0/transforms/0/fn",
            "enum"),
        arg(
            "ponteiro inválido",
            set(Fixtures.insert(), ROOT + "/fields/0/source", "numero"),
            ROOT + "/fields/0/source",
            "pattern"),
        arg(
            "nó sem campos",
            set(Fixtures.insert(), ROOT + "/fields", List.of()),
            ROOT + "/fields",
            "minItems"),
        arg(
            "parâmetro com 'in' inválido",
            set(Fixtures.insert(), "/request/params/0/in", "header"),
            "/request/params/0/in",
            "enum"),
        arg(
            "limite de corpo acima de 5 MB",
            set(Fixtures.insert(), "/request/maxBodyBytes", 6_000_000),
            "/request/maxBodyBytes",
            "maximum"),
        arg(
            "status de sucesso inválido",
            set(Fixtures.insert(), "/response/successStatus", 418),
            "/response/successStatus",
            "enum"),
        arg(
            "script grande demais",
            set(Fixtures.insert(), "/scripts/beforeExecute", "x".repeat(70_000)),
            "/scripts/beforeExecute",
            "maxLength"),
        arg(
            "consulta com nome inválido",
            set(Fixtures.insert(), "/namedQueries/0/name", "Cliente-Por Doc"),
            "/namedQueries/0/name",
            "pattern"),
        arg(
            "teste sem expect",
            remove(Fixtures.insert(), "/tests/0/expect"),
            "/tests/0/expect",
            "required"),
        arg(
            "select em escrita",
            set(
                Fixtures.insert(),
                "/operation/select",
                Map.of("table", "dbo.t", "columns", List.of("a"))),
            "/operation",
            "not"),
        // semântica
        arg(
            "método e operação incompatíveis",
            set(Fixtures.insert(), "/integration/method", "GET"),
            "/operation/type",
            "method_operation_mismatch"),
        arg(
            "variável de rota não declarada",
            set(Fixtures.insert(), "/integration/route", "/pedidos/{id}"),
            "/integration/route",
            "undeclared_path_param"),
        arg(
            "parâmetro de caminho fora da rota",
            set(Fixtures.select(), "/integration/route", "/pedidos"),
            "/request/params/0/name",
            "path_param_not_in_route"),
        arg(
            "parâmetro de caminho opcional",
            set(Fixtures.select(), "/request/params/0/required", false),
            "/request/params/0/required",
            "path_param_must_be_required"),
        arg(
            "parâmetro repetido",
            set(
                Fixtures.insert(),
                "/request/params/1",
                Map.of("name", "loja", "in", "query", "type", "string")),
            "/request/params/1/name",
            "duplicate"),
        arg(
            "escrita sem schema do corpo",
            remove(Fixtures.insert(), "/request/bodySchema"),
            "/request/bodySchema",
            "body_schema_required"),
        arg(
            "raiz com arrayPath",
            set(Fixtures.insert(), ROOT + "/arrayPath", "/x"),
            ROOT + "/arrayPath",
            "root_has_array_path"),
        arg(
            "filho sem arrayPath",
            remove(Fixtures.insert(), ROOT + "/children/0/arrayPath"),
            ROOT + "/children/0/arrayPath",
            "array_path_required"),
        arg(
            "filho sem chave estrangeira",
            remove(Fixtures.insert(), ROOT + "/children/0/foreignKey"),
            ROOT + "/children/0/foreignKey",
            "foreign_key_required"),
        arg(
            "raiz com chave estrangeira",
            set(Fixtures.insert(), ROOT + "/foreignKey", "x"),
            ROOT + "/foreignKey",
            "root_has_foreign_key"),
        arg(
            "pai sem chave primária",
            remove(Fixtures.insert(), ROOT + "/primaryKey"),
            ROOT + "/primaryKey",
            "primary_key_required_for_children"),
        arg(
            "coluna repetida",
            set(Fixtures.insert(), ROOT + "/fields/1/column", "nr_pedido"),
            ROOT + "/fields/1/column",
            "duplicate_column"),
        arg(
            "chave estrangeira mapeada",
            set(Fixtures.insert(), ROOT + "/children/0/fields/0/column", "pedido_id"),
            ROOT + "/children/0/foreignKey",
            "foreign_key_is_mapped"),
        arg(
            "keyColumns em INSERT",
            set(Fixtures.insert(), ROOT + "/keyColumns", List.of("nr_pedido")),
            ROOT + "/keyColumns",
            "key_columns_not_allowed"),
        arg(
            "UPSERT sem keyColumns",
            remove(Fixtures.upsert(), ROOT + "/keyColumns"),
            ROOT + "/keyColumns",
            "key_columns_required"),
        arg(
            "chave não mapeada",
            set(Fixtures.upsert(), ROOT + "/keyColumns", List.of("outra")),
            ROOT + "/keyColumns/0",
            "key_column_not_mapped"),
        arg(
            "filhos em UPSERT",
            set(
                Fixtures.upsert(),
                ROOT + "/children",
                List.of(
                    Map.of(
                        "table",
                        "dbo.x",
                        "arrayPath",
                        "/a",
                        "foreignKey",
                        "f",
                        "fields",
                        List.of(Map.of("column", "c", "constant", 1))))),
            ROOT + "/children",
            "children_only_for_insert"),
        arg(
            "substring com argumentos inválidos",
            set(
                Fixtures.insert(),
                ROOT + "/fields/0/transforms/0",
                Map.of("fn", "substring", "args", List.of("a", 2))),
            ROOT + "/fields/0/transforms/0/args",
            "invalid_transform"),
        arg(
            "trim com argumentos",
            set(
                Fixtures.insert(),
                ROOT + "/fields/0/transforms/0",
                Map.of("fn", "trim", "args", List.of(1))),
            ROOT + "/fields/0/transforms/0/args",
            "invalid_transform"),
        arg(
            "campo usa parâmetro não declarado",
            set(Fixtures.insert(), ROOT + "/fields/2/param", "inexistente"),
            ROOT + "/fields/2/param",
            "unknown_param"),
        arg(
            "filtro com parâmetro não declarado",
            set(Fixtures.select(), "/operation/select/filters/1/param", "inexistente"),
            "/operation/select/filters/1/param",
            "unknown_param"),
        arg(
            "ordenável não retornada",
            set(Fixtures.select(), "/operation/select/sortable", List.of("id", "oculta")),
            "/operation/select/sortable/1",
            "sortable_not_selected"),
        arg(
            "ordenação padrão fora das ordenáveis",
            set(Fixtures.select(), "/operation/select/defaultSort/column", "status"),
            "/operation/select/defaultSort/column",
            "default_sort_not_allowed"),
        arg(
            "página padrão acima do máximo",
            set(Fixtures.select(), "/operation/select/pageSize/default", 900),
            "/operation/select/pageSize/default",
            "page_default_above_max"),
        arg(
            "consulta repetida",
            set(
                Fixtures.insert(),
                "/namedQueries/1",
                Map.of("name", "cliente_por_documento", "sql", "SELECT 1")),
            "/namedQueries/1/name",
            "duplicate"),
        arg(
            "teste repetido",
            set(
                Fixtures.insert(),
                "/tests/1",
                Map.of("name", "cria pedido com dois itens", "expect", Map.of("status", 200))),
            "/tests/1/name",
            "duplicate"),
        arg(
            "teste com parâmetro não declarado",
            set(Fixtures.insert(), "/tests/0/request/params/nada", "x"),
            "/tests/0/request/params/nada",
            "unknown_param"),
        // schema do corpo
        arg(
            "$ref remoto",
            set(
                Fixtures.insert(),
                bodyProps + "/numero",
                Map.of("$ref", "http://evil.test/schema.json")),
            bodyProps + "/numero/$ref",
            "remote_ref"),
        arg(
            "$ref para arquivo",
            set(Fixtures.insert(), bodyProps + "/numero", Map.of("$ref", "file:///etc/passwd")),
            bodyProps + "/numero/$ref",
            "remote_ref"),
        arg(
            "$ref relativo externo",
            set(Fixtures.insert(), bodyProps + "/numero", Map.of("$ref", "outro.json#/x")),
            bodyProps + "/numero/$ref",
            "remote_ref"),
        arg(
            "regex catastrófica",
            set(Fixtures.insert(), bodyProps + "/numero/pattern", "^(a+)+$"),
            bodyProps + "/numero/pattern",
            "unsafe_pattern"),
        arg(
            "regex catastrófica com alternância",
            set(Fixtures.insert(), bodyProps + "/numero/pattern", "^(a|b*)*c"),
            bodyProps + "/numero/pattern",
            "unsafe_pattern"),
        arg(
            "regex grande demais",
            set(Fixtures.insert(), bodyProps + "/numero/pattern", "a".repeat(201)),
            bodyProps + "/numero/pattern",
            "pattern_too_long"),
        arg(
            "regex inválida",
            set(Fixtures.insert(), bodyProps + "/numero/pattern", "[abc"),
            bodyProps + "/numero/pattern",
            "invalid_pattern"),
        arg(
            "dialeto antigo",
            set(
                Fixtures.insert(),
                "/request/bodySchema/$schema",
                "http://json-schema.org/draft-07/schema#"),
            "/request/bodySchema/$schema",
            "unsupported_dialect"),
        arg(
            "$id proibido",
            set(Fixtures.insert(), "/request/bodySchema/$id", "https://evil.test/x"),
            "/request/bodySchema/$id",
            "forbidden_keyword"),
        arg(
            "tipo inexistente no schema do corpo",
            set(Fixtures.insert(), bodyProps + "/numero/type", "texto"),
            bodyProps + "/numero/type",
            "invalid_json_schema"));
  }

  private static Arguments arg(String name, ObjectNode doc, String path, String code) {
    return Arguments.of(name, doc, path, code);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defects")
  void reportsTheDefectAtTheExactField(String name, ObjectNode doc, String path, String code) {
    List<FieldError> errors = errorsOf(doc);

    assertThat(errors)
        .as(name)
        .extracting(FieldError::path, FieldError::code)
        .contains(org.assertj.core.groups.Tuple.tuple(path, code));
  }

  @Test
  void errorMessagesNeverEchoInjectedValues() {
    ObjectNode doc = set(Fixtures.insert(), ROOT + "/fields/0/column", "x]; DROP TABLE t;--");
    set(doc, ROOT + "/table", "tb; DROP TABLE x");

    String all =
        errorsOf(doc).stream()
            .map(e -> e.path() + e.code() + e.message())
            .reduce("", String::concat);

    assertThat(all).doesNotContain("DROP");
  }

  @Test
  void reportsEveryDefectAtOnceSortedByPath() {
    ObjectNode doc = Fixtures.insert();
    remove(doc, "/integration/key");
    set(doc, "/integration/method", "TRACE");
    set(doc, ROOT + "/fields/1/column", "bad col");

    List<FieldError> errors = errorsOf(doc);

    assertThat(errors)
        .extracting(FieldError::path)
        .contains("/integration/key", "/integration/method", ROOT + "/fields/1/column");
    assertThat(errors).isSorted();
  }

  @Test
  void aTreeDeeperThanFiveLevelsIsRefused() {
    ObjectNode doc = Fixtures.insert();
    Object leaf =
        Map.of(
            "table",
            "dbo.n6",
            "arrayPath",
            "/a",
            "foreignKey",
            "fk",
            "fields",
            List.of(Map.of("column", "c", "constant", 1)));
    Object n5 =
        Map.of(
            "table",
            "dbo.n5",
            "arrayPath",
            "/a",
            "foreignKey",
            "fk",
            "primaryKey",
            "id",
            "fields",
            List.of(Map.of("column", "c", "constant", 1)),
            "children",
            List.of(leaf));
    Object n4 =
        Map.of(
            "table",
            "dbo.n4",
            "arrayPath",
            "/a",
            "foreignKey",
            "fk",
            "primaryKey",
            "id",
            "fields",
            List.of(Map.of("column", "c", "constant", 1)),
            "children",
            List.of(n5));
    Object n3 =
        Map.of(
            "table",
            "dbo.n3",
            "arrayPath",
            "/a",
            "foreignKey",
            "fk",
            "primaryKey",
            "id",
            "fields",
            List.of(Map.of("column", "c", "constant", 1)),
            "children",
            List.of(n4));
    set(doc, ROOT + "/children/0/children", List.of(n3));
    set(doc, ROOT + "/children/0/primaryKey", "id");

    assertThat(errorsOf(doc)).extracting(FieldError::code).contains("too_deep");
  }

  // ---------- limites e entrada hostil ----------

  @Test
  void refusesEmptyOversizedMalformedAndAmbiguousInput() {
    for (byte[] bad : new byte[][] {null, new byte[0]}) {
      assertThatThrownBy(() -> service.parse(bad)).isInstanceOf(SnapshotValidationException.class);
    }
    byte[] huge = new byte[SnapshotService.MAX_BYTES + 1];
    java.util.Arrays.fill(huge, (byte) ' ');
    assertCode(huge, "too_large");
    assertCode("{ não é json".getBytes(StandardCharsets.UTF_8), "invalid_json");
    assertCode(
        "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8),
        "invalid_json");
    assertCode("[1,2,3]".getBytes(StandardCharsets.UTF_8), "not_an_object");
    assertCode("\"texto\"".getBytes(StandardCharsets.UTF_8), "not_an_object");
    assertCode(
        ("{\"a\":".repeat(100) + "1" + "}".repeat(100)).getBytes(StandardCharsets.UTF_8),
        "invalid_json");
  }

  private void assertCode(byte[] json, String code) {
    assertThatThrownBy(() -> service.parse(json))
        .isInstanceOfSatisfying(
            SnapshotValidationException.class,
            e -> assertThat(e.errors()).extracting(FieldError::code).contains(code));
  }

  @Test
  void versionsAreCheckedBeforeValidation() {
    assertCode(Fixtures.bytes(set(Fixtures.insert(), "/schemaVersion", 2)), "future_version");
    assertCode(Fixtures.bytes(set(Fixtures.insert(), "/schemaVersion", 0)), "invalid_version");
    assertCode(Fixtures.bytes(remove(Fixtures.insert(), "/schemaVersion")), "invalid_version");
    assertCode(Fixtures.bytes(set(Fixtures.insert(), "/schemaVersion", "1")), "invalid_version");
  }

  @Test
  void theJsonSchemaItselfIsAValidDraft2020Schema() throws Exception {
    var registry =
        com.networknt.schema.SchemaRegistry.withDefaultDialect(
            com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
    var meta =
        registry.getSchema(
            com.networknt.schema.SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    try (var stream = getClass().getResourceAsStream("/snapshot/snapshot-v1.schema.json")) {
      assertThat(meta.validate(Fixtures.MAPPER.readTree(stream))).isEmpty();
    }
  }
}
