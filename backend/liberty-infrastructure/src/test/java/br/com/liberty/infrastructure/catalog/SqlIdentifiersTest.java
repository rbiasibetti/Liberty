package br.com.liberty.infrastructure.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SqlIdentifiersTest {

  private static ColumnInfo col(String name, int ordinal) {
    return new ColumnInfo(
        name, "varchar", ColumnInfo.Kind.STRING, 10, 0, true, false, false, ordinal, 0);
  }

  private static final TableInfo TABLE =
      new TableInfo(
          "dbo",
          "Tb_Cliente",
          TableInfo.Kind.TABLE,
          List.of(col("Id", 1), col("Nome", 2), col("nome_x", 3)));

  @Test
  void usesTheCatalogSpellingBetweenBrackets() {
    assertThat(SqlIdentifiers.table(TABLE)).isEqualTo("[dbo].[Tb_Cliente]");
    assertThat(SqlIdentifiers.column(TABLE, "id")).isEqualTo("[Id]");
    assertThat(SqlIdentifiers.column(TABLE, "NOME")).isEqualTo("[Nome]");
    assertThat(SqlIdentifiers.column(TABLE, "nome_x")).isEqualTo("[nome_x]");
  }

  @Test
  void unknownColumnsAreRefusedWithoutEchoingTheInput() {
    for (String bad :
        new String[] {
          "nao_existe", "", " ", "Nome) FROM x --", "Nome]; DROP TABLE t;--", "*", "Id,Nome", null
        }) {
      assertThatThrownBy(() -> SqlIdentifiers.column(TABLE, bad))
          .isInstanceOf(UnknownDatabaseObjectException.class)
          .satisfies(
              e -> {
                if (bad != null && !bad.isBlank()) {
                  assertThat(e.getMessage()).doesNotContain(bad);
                }
              });
    }
  }

  @Test
  void exactSpellingWinsWhenTheCollationIsCaseSensitive() {
    TableInfo cs =
        new TableInfo("dbo", "t", TableInfo.Kind.TABLE, List.of(col("nome", 1), col("Nome", 2)));

    assertThat(SqlIdentifiers.column(cs, "Nome")).isEqualTo("[Nome]");
    assertThat(SqlIdentifiers.column(cs, "nome")).isEqualTo("[nome]");
    assertThat(cs.column("NOME")).as("ambíguo: duas colunas diferem só em maiúsculas").isEmpty();
  }

  @Test
  void aCatalogNameOutsideThePlainPatternNeverBecomesSql() {
    for (String weird : new String[] {"Order Details", "tb-hifen", "x]y", "a;b", "", "ação"}) {
      TableInfo table = new TableInfo("dbo", weird, TableInfo.Kind.TABLE, List.of(col("id", 1)));
      assertThatThrownBy(() -> SqlIdentifiers.table(table))
          .isInstanceOf(InvalidIdentifierException.class);
    }
    TableInfo weirdColumn = new TableInfo("dbo", "t", TableInfo.Kind.TABLE, List.of(col("a b", 1)));
    assertThatThrownBy(() -> SqlIdentifiers.column(weirdColumn, "a b"))
        .isInstanceOf(InvalidIdentifierException.class);
  }

  @Test
  void messagesNeverCarryUnsafeNames() {
    assertThat(SqlIdentifiers.forMessage("tb_cliente")).isEqualTo("tb_cliente");
    assertThat(SqlIdentifiers.forMessage("x]; DROP")).isEqualTo("<inválido>");
    assertThat(SqlIdentifiers.forMessage(null)).isEqualTo("<inválido>");
  }

  /**
   * Fuzz: nenhuma entrada, por mais estranha, produz SQL fora do formato [nome].[nome] / [nome].
   */
  @Test
  void fuzzNeverProducesAnythingButBracketedPlainNames() {
    Random random = new Random(42);
    String alphabet = "abcXYZ_09 ;'\"[]-/*%\\.\n\t\u0000é中()=,@#$&|";
    Pattern table = Pattern.compile("\\[[A-Za-z_]\\w*]\\.\\[[A-Za-z_]\\w*]");
    Pattern column = Pattern.compile("\\[[A-Za-z_]\\w*]");
    int accepted = 0;
    for (int i = 0; i < 20_000; i++) {
      StringBuilder input = new StringBuilder();
      for (int n = random.nextInt(14); n >= 0; n--) {
        input.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      try {
        TableRef ref = TableRef.parse(input.toString());
        TableInfo info =
            new TableInfo(
                ref.schema(), ref.name(), TableInfo.Kind.TABLE, List.of(col(ref.name(), 1)));
        assertThat(SqlIdentifiers.table(info)).matches(table);
        assertThat(SqlIdentifiers.column(info, ref.name())).matches(column);
        accepted++;
      } catch (InvalidIdentifierException | UnknownDatabaseObjectException expected) {
        // recusado: é o comportamento esperado para a maioria das entradas
      }
    }
    assertThat(accepted).isPositive();
  }
}
