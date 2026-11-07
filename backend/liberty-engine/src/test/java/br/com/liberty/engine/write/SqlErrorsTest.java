package br.com.liberty.engine.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SqlErrorsTest {

  private static SQLException sql(int code) {
    return new SQLException("texto do servidor com detalhes", "S0001", code);
  }

  @Test
  void duplicateKeysAreConflicts() {
    for (int code : new int[] {2627, 2601}) {
      var problem = SqlErrors.translate(new DataIntegrityViolationException("x", sql(code)));
      assertThat(problem).isPresent();
      assertThat(problem.get().status()).isEqualTo(409);
      assertThat(problem.get().type()).endsWith("conflict");
      assertThat(problem.get().getMessage()).doesNotContain("servidor");
    }
  }

  @Test
  void constraintViolationsAreConflictsToo() {
    var problem = SqlErrors.translate(new DataIntegrityViolationException("x", sql(547)));

    assertThat(problem.orElseThrow().status()).isEqualTo(409);
    assertThat(problem.get().type()).endsWith("constraint-violation");
  }

  @Test
  void valuesTheDatabaseCannotStoreAreUnprocessable() {
    for (int code : new int[] {515, 2628, 8152, 8115, 245}) {
      assertThat(SqlErrors.translate(sql(code)).orElseThrow().status()).isEqualTo(422);
    }
  }

  @Test
  void deadlocksAskTheClientToRetry() {
    var problem = SqlErrors.translate(sql(1205)).orElseThrow();

    assertThat(problem.status()).isEqualTo(503);
    assertThat(problem.headers()).containsEntry("Retry-After", "1");
  }

  @Test
  void chainedBatchErrorsAreFound() {
    var first = new SQLException("lote", "S0001", 0);
    first.setNextException(sql(2627));
    var batch = new BatchUpdateException("falha", new int[0], first);

    assertThat(SqlErrors.translate(new RuntimeException("x", batch)).orElseThrow().status())
        .isEqualTo(409);
  }

  @Test
  void unknownErrorsAreLeftToTheCaller() {
    assertThat(SqlErrors.translate(sql(9999))).isEmpty();
    assertThat(SqlErrors.translate(new IllegalStateException("sem sql"))).isEmpty();
  }

  private static ColumnInfo column(String type, ColumnInfo.Kind kind, int size, int scale) {
    return new ColumnInfo("c", type, kind, size, scale, false, false, false, 1, 1);
  }

  @Test
  void keyTypesAreDeclaredForTheCapturedKey() {
    assertThat(SqlTypes.declaration(column("bigint identity", ColumnInfo.Kind.INTEGER, 19, 0)))
        .isEqualTo("bigint");
    assertThat(SqlTypes.declaration(column("uniqueidentifier", ColumnInfo.Kind.UUID, 36, 0)))
        .isEqualTo("uniqueidentifier");
    assertThat(SqlTypes.declaration(column("varchar", ColumnInfo.Kind.STRING, 40, 0)))
        .isEqualTo("varchar(40)");
    assertThat(SqlTypes.declaration(column("nvarchar", ColumnInfo.Kind.STRING, 2147483647, 0)))
        .isEqualTo("nvarchar(max)");
    assertThat(SqlTypes.declaration(column("decimal", ColumnInfo.Kind.DECIMAL, 18, 2)))
        .isEqualTo("decimal(18,2)");
    assertThatThrownBy(
            () -> SqlTypes.declaration(column("datetime2", ColumnInfo.Kind.DATE_TIME, 27, 7)))
        .isInstanceOf(WritePlanner.ConfigurationException.class);
    assertThatThrownBy(
            () -> SqlTypes.declaration(column("sysname", ColumnInfo.Kind.STRING, 128, 0)))
        .isInstanceOf(WritePlanner.ConfigurationException.class);
  }

  @Test
  void everyKindHasAJdbcTypeForNulls() {
    for (ColumnInfo.Kind kind : ColumnInfo.Kind.values()) {
      assertThat(SqlTypes.jdbcType(kind)).isNotEqualTo(Types.NULL);
    }
  }
}
