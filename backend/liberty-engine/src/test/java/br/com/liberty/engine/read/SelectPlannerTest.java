package br.com.liberty.engine.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.snapshot.model.Select;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SelectPlannerTest {

  private static ColumnInfo col(String name, ColumnInfo.Kind kind, int ordinal, int pk) {
    return new ColumnInfo(name, "t", kind, 10, 2, true, false, false, ordinal, pk);
  }

  private static final TableInfo TABLE =
      new TableInfo(
          "dbo",
          "tb_pedido",
          TableInfo.Kind.TABLE,
          List.of(
              col("id", ColumnInfo.Kind.INTEGER, 1, 1),
              col("nr_pedido", ColumnInfo.Kind.STRING, 2, 0),
              col("cliente_id", ColumnInfo.Kind.INTEGER, 3, 0),
              col("vl_total", ColumnInfo.Kind.DECIMAL, 4, 0),
              col("dt_pedido", ColumnInfo.Kind.DATE_TIME, 5, 0),
              col("status", ColumnInfo.Kind.STRING, 6, 0),
              col("anexo", ColumnInfo.Kind.BINARY, 7, 0)));

  private static Select.Filter filter(
      String param, String column, String operator, Boolean required) {
    return new Select.Filter(param, column, operator, required);
  }

  private static Select select(
      List<Select.Filter> filters, List<String> sortable, Select.Sort sort, Boolean single) {
    return new Select(
        "dbo.tb_pedido",
        List.of("id", "nr_pedido", "vl_total"),
        filters,
        sortable,
        sort,
        null,
        single);
  }

  private static ParsedInput input(Map<String, String> values, int limit, int offset, String sort) {
    return new ParsedInput(values, limit, offset, sort);
  }

  @Test
  void buildsAPagedSelectWithOnlyCatalogIdentifiersAndPlaceholders() {
    Select select =
        select(
            List.of(filter("cliente", "cliente_id", "=", null)),
            List.of("id", "vl_total"),
            new Select.Sort("id", "DESC"),
            null);

    SelectPlan plan =
        SelectPlanner.plan(select, TABLE, input(Map.of("cliente", "42"), 20, 40, null));

    assertThat(plan.sql())
        .isEqualTo(
            "SELECT [id], [nr_pedido], [vl_total] FROM [dbo].[tb_pedido] WHERE [cliente_id] = :p0"
                + " ORDER BY [id] DESC OFFSET :offset ROWS FETCH NEXT :fetch ROWS ONLY");
    assertThat(plan.params())
        .containsEntry("p0", 42L)
        .containsEntry("offset", 40)
        .containsEntry("fetch", 21);
    assertThat(plan.limit()).isEqualTo(20);
    assertThat(plan.offset()).isEqualTo(40);
    assertThat(plan.columns())
        .extracting(ColumnInfo::name)
        .containsExactly("id", "nr_pedido", "vl_total");
    assertThat(plan.single()).isFalse();
  }

  @Test
  void supportsEveryOperator() {
    Select select =
        select(
            List.of(
                filter("a", "cliente_id", "<>", null),
                filter("b", "vl_total", ">=", null),
                filter("c", "vl_total", "<", null),
                filter("d", "nr_pedido", "LIKE_PREFIX", null),
                filter("e", "id", "IN", null),
                filter("f", "dt_pedido", "BETWEEN", null)),
            List.of(),
            null,
            null);

    SelectPlan plan =
        SelectPlanner.plan(
            select,
            TABLE,
            input(
                Map.of(
                    "a",
                    "1",
                    "b",
                    "10.5",
                    "c",
                    "99",
                    "d",
                    "P-0",
                    "e",
                    "1, 2,3",
                    "f",
                    "2026-01-01,2026-01-31"),
                10,
                0,
                null));

    assertThat(plan.sql())
        .contains(
            "[cliente_id] <> :p0",
            "[vl_total] >= :p1",
            "[vl_total] < :p2",
            "[nr_pedido] LIKE :p3 ESCAPE '\\'",
            "[id] IN (:p4, :p5, :p6)",
            "[dt_pedido] BETWEEN :p7 AND :p8");
    assertThat(plan.params())
        .containsEntry("p1", new BigDecimal("10.5"))
        .containsEntry("p3", "P-0%")
        .containsEntry("p5", 2L);
  }

  @Test
  void likePrefixEscapesWildcardsSoTheValueIsAlwaysLiteral() {
    Select select =
        select(List.of(filter("d", "nr_pedido", "LIKE_PREFIX", null)), List.of(), null, null);

    SelectPlan plan =
        SelectPlanner.plan(select, TABLE, input(Map.of("d", "50%_[x]\\"), 10, 0, null));

    assertThat(plan.params().get("p0")).isEqualTo("50\\%\\_\\[x]\\\\%");
  }

  @Test
  void requiredFiltersAndBadValuesAreReportedPerParameter() {
    Select select =
        select(
            List.of(
                filter("cliente", "cliente_id", "=", true),
                filter("total", "vl_total", ">", null),
                filter("datas", "dt_pedido", "BETWEEN", null),
                filter("ids", "id", "IN", null)),
            List.of(),
            null,
            null);

    assertThatThrownBy(
            () ->
                SelectPlanner.plan(
                    select,
                    TABLE,
                    input(Map.of("total", "x", "datas", "2026-01-01", "ids", "1,,2"), 10, 0, null)))
        .isInstanceOfSatisfying(
            ProblemException.class,
            e ->
                assertThat(e.violations())
                    .extracting(v -> v.parameter() + ":" + v.code())
                    .containsExactlyInAnyOrder(
                        "cliente:required",
                        "total:invalid_value",
                        "datas:invalid_value",
                        "ids:invalid_value"));
  }

  @Test
  void listsAreBoundedAndBetweenNeedsExactlyTwoValues() {
    Select in = select(List.of(filter("ids", "id", "IN", null)), List.of(), null, null);
    String hundredOne = String.join(",", java.util.Collections.nCopies(101, "1"));
    String hundred = String.join(",", java.util.Collections.nCopies(100, "1"));

    assertThatThrownBy(
            () -> SelectPlanner.plan(in, TABLE, input(Map.of("ids", hundredOne), 10, 0, null)))
        .isInstanceOf(ProblemException.class);
    assertThat(SelectPlanner.plan(in, TABLE, input(Map.of("ids", hundred), 10, 0, null)).params())
        .hasSize(102);
    Select between = select(List.of(filter("f", "id", "BETWEEN", null)), List.of(), null, null);
    assertThatThrownBy(
            () -> SelectPlanner.plan(between, TABLE, input(Map.of("f", "1,2,3"), 10, 0, null)))
        .isInstanceOf(ProblemException.class);
  }

  @Test
  void sortFollowsTheSnapshotAndTheClientCanOnlyPickFromIt() {
    Select select =
        select(List.of(), List.of("id", "vl_total"), new Select.Sort("id", "ASC"), null);

    assertThat(SelectPlanner.plan(select, TABLE, input(Map.of(), 10, 0, "-VL_TOTAL")).sql())
        .contains("ORDER BY [vl_total] DESC, [id] ASC OFFSET");
    assertThat(SelectPlanner.plan(select, TABLE, input(Map.of(), 10, 0, "vl_total")).sql())
        .contains("ORDER BY [vl_total] ASC, [id] ASC");
    assertThat(SelectPlanner.plan(select, TABLE, input(Map.of(), 10, 0, null)).sql())
        .contains("ORDER BY [id] ASC OFFSET");
    for (String bad : new String[] {"status", "-status", "anexo", "nr_pedido", "id,vl_total"}) {
      assertThatThrownBy(() -> SelectPlanner.plan(select, TABLE, input(Map.of(), 10, 0, bad)))
          .isInstanceOfSatisfying(
              ProblemException.class,
              e ->
                  assertThat(e.violations())
                      .extracting(v -> v.code())
                      .containsExactly("sort_not_allowed"));
    }
  }

  @Test
  void orderingIsAlwaysDeterministic() {
    Select none = select(List.of(), List.of(), null, null);
    TableInfo noPk =
        new TableInfo(
            "dbo",
            "sem_pk",
            TableInfo.Kind.TABLE,
            List.of(
                col("a", ColumnInfo.Kind.STRING, 1, 0), col("b", ColumnInfo.Kind.STRING, 2, 0)));
    Select selectAll =
        new Select("dbo.sem_pk", List.of("a", "b"), List.of(), List.of(), null, null, null);

    assertThat(SelectPlanner.plan(none, TABLE, input(Map.of(), 10, 0, null)).sql())
        .contains("ORDER BY [id] ASC");
    assertThat(SelectPlanner.plan(selectAll, noPk, input(Map.of(), 10, 0, null)).sql())
        .contains("ORDER BY [a] ASC, [b] ASC");
  }

  @Test
  void singleFetchesAtMostTwoRowsToDetectAmbiguity() {
    Select select =
        select(List.of(filter("numero", "nr_pedido", "=", true)), List.of(), null, true);

    SelectPlan plan =
        SelectPlanner.plan(select, TABLE, input(Map.of("numero", "P-1"), 100, 500, null));

    assertThat(plan.single()).isTrue();
    assertThat(plan.params()).containsEntry("fetch", 2).containsEntry("offset", 0);
    assertThat(plan.limit()).isEqualTo(1);
  }

  @Test
  void configurationMistakesAreNotClientErrors() {
    Select likeOnNumber =
        select(List.of(filter("x", "id", "LIKE_PREFIX", null)), List.of(), null, null);
    Select compareBinary = select(List.of(filter("x", "anexo", "=", null)), List.of(), null, null);

    assertThatThrownBy(
            () -> SelectPlanner.plan(likeOnNumber, TABLE, input(Map.of("x", "1"), 10, 0, null)))
        .isInstanceOf(ValueBinder.UnsupportedColumnException.class);
    assertThatThrownBy(
            () -> SelectPlanner.plan(compareBinary, TABLE, input(Map.of("x", "1"), 10, 0, null)))
        .isInstanceOf(ValueBinder.UnsupportedColumnException.class);
  }

  // ---------- fuzz: nenhuma entrada escapa para o texto do SQL ----------

  private static final String COND =
      "(?:ID (?:=|<>|>|>=|<|<=) P|ID LIKE P ESCAPE '\\\\'|ID IN \\(P(?:, P)*\\)|ID BETWEEN P AND P)";
  private static final Pattern SKELETON =
      Pattern.compile(
          "^SELECT ID(, ID)* FROM ID\\.ID( WHERE "
              + COND
              + "( AND "
              + COND
              + ")*)? ORDER BY ID (ASC|DESC)(, ID (ASC|DESC))* OFFSET P ROWS FETCH NEXT P ROWS ONLY$");

  private static String skeleton(String sql) {
    return sql.replaceAll("\\[[A-Za-z_][A-Za-z0-9_]*]", "ID").replaceAll(":[a-z][a-z0-9]*", "P");
  }

  @Test
  void fuzzedValuesNeverReachTheSqlText() {
    Select select =
        select(
            List.of(
                filter("s", "status", "=", null),
                filter("n", "cliente_id", "=", null),
                filter("p", "nr_pedido", "LIKE_PREFIX", null),
                filter("i", "id", "IN", null),
                filter("b", "dt_pedido", "BETWEEN", null),
                filter("t", "vl_total", ">=", null)),
            List.of("id", "vl_total"),
            new Select.Sort("id", "ASC"),
            null);
    String alphabet = "abcXYZ_09 ;'\"[]-/*%\\.,\n\t\u0000é中()=@#$&|:!?";
    String[] payloads = {
      "' OR 1=1 --",
      "1; DROP TABLE dbo.tb_pedido;--",
      "x' UNION SELECT name FROM sys.tables--",
      "\u0000",
      "%",
      "_",
      "]",
      "[",
      "--",
      "/*",
      "\n",
      "1,2); DELETE FROM t;--",
      ":p0",
      ":offset ROWS",
      "\\"
    };
    Random random = new Random(7);
    List<String> inputs = new ArrayList<>(List.of(payloads));
    for (int i = 0; i < 3_000; i++) {
      StringBuilder sb = new StringBuilder();
      for (int n = random.nextInt(18); n >= 0; n--) {
        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      inputs.add(sb.toString());
    }
    Map<String, String> valid =
        Map.of(
            "s", "ok", "n", "1", "p", "P-", "i", "1,2", "b", "2026-01-01,2026-01-02", "t", "1.5");
    int planned = 0;
    for (String text : inputs) {
      for (String key : valid.keySet()) {
        Map<String, String> values = new java.util.HashMap<>(valid);
        values.put(key, text);
        try {
          SelectPlan plan = SelectPlanner.plan(select, TABLE, input(values, 10, 0, null));
          assertThat(skeleton(plan.sql())).matches(SKELETON);
          planned++;
        } catch (ProblemException | ValueBinder.UnsupportedColumnException expected) {
          // recusado: esperado para a maioria das entradas aleatórias nos filtros tipados
        }
      }
      try {
        SelectPlan plan = SelectPlanner.plan(select, TABLE, input(valid, 10, 0, text));
        assertThat(skeleton(plan.sql())).matches(SKELETON);
      } catch (ProblemException expected) {
        // ordenação inválida ou fora da lista: recusada
      }
    }
    assertThat(planned)
        .as("os filtros de texto aceitam qualquer texto como valor")
        .isGreaterThan(6_000);
  }
}
