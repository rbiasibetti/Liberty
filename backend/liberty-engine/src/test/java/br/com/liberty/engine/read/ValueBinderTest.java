package br.com.liberty.engine.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.liberty.infrastructure.catalog.ColumnInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValueBinderTest {

  private static ColumnInfo column(ColumnInfo.Kind kind) {
    return new ColumnInfo("c", "t", kind, 10, 2, true, false, false, 1, 0);
  }

  private static Object bind(ColumnInfo.Kind kind, String raw) {
    return ValueBinder.bind(column(kind), raw);
  }

  @Test
  void bindsEachKindToItsJavaType() {
    assertThat(bind(ColumnInfo.Kind.STRING, "x'; DROP")).isEqualTo("x'; DROP");
    assertThat(bind(ColumnInfo.Kind.INTEGER, "-42")).isEqualTo(-42L);
    assertThat(bind(ColumnInfo.Kind.DECIMAL, "12.340")).isEqualTo(new BigDecimal("12.340"));
    assertThat(bind(ColumnInfo.Kind.FLOAT, "1.5")).isEqualTo(1.5d);
    assertThat(bind(ColumnInfo.Kind.BOOLEAN, "true")).isEqualTo(true);
    assertThat(bind(ColumnInfo.Kind.BOOLEAN, "0")).isEqualTo(false);
    assertThat(bind(ColumnInfo.Kind.DATE, "2026-11-06")).isEqualTo(LocalDate.of(2026, 11, 6));
    assertThat(bind(ColumnInfo.Kind.TIME, "10:15:30")).isEqualTo(LocalTime.of(10, 15, 30));
    assertThat(bind(ColumnInfo.Kind.DATE_TIME, "2026-11-06T10:15:30"))
        .isEqualTo(LocalDateTime.of(2026, 11, 6, 10, 15, 30));
    assertThat(bind(ColumnInfo.Kind.DATE_TIME, "2026-11-06"))
        .as("só a data vale meia-noite")
        .isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
    assertThat(bind(ColumnInfo.Kind.DATE_TIME_OFFSET, "2026-11-06T10:15:30-03:00"))
        .isEqualTo(OffsetDateTime.parse("2026-11-06T10:15:30-03:00"));
    assertThat(bind(ColumnInfo.Kind.UUID, "123e4567-e89b-12d3-a456-426614174000"))
        .isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
  }

  @Test
  void aDateWithoutTimeIsMidnightOrEndOfDayDependingOnTheBound() {
    ColumnInfo dateTime = column(ColumnInfo.Kind.DATE_TIME);

    assertThat(ValueBinder.bind(dateTime, "2026-01-07", false))
        .isEqualTo(LocalDateTime.of(2026, 1, 7, 0, 0));
    assertThat(ValueBinder.bind(dateTime, "2026-01-07", true))
        .isEqualTo(LocalDate.of(2026, 1, 7).atTime(LocalTime.MAX));
    assertThat(ValueBinder.bind(dateTime, "2026-01-07T10:00:00", true))
        .as("com hora, vale a hora")
        .isEqualTo(LocalDateTime.of(2026, 1, 7, 10, 0));
    assertThat(ValueBinder.bind(column(ColumnInfo.Kind.DATE), "2026-01-07", true))
        .isEqualTo(LocalDate.of(2026, 1, 7));
  }

  @Test
  void refusesValuesThatDoNotFitTheColumn() {
    String[][] bad = {
      {"INTEGER", "abc"},
      {"INTEGER", "9223372036854775808"},
      {"INTEGER", "1.5"},
      {"DECIMAL", "1,5"},
      {"DECIMAL", "x"},
      {"FLOAT", "NaN"},
      {"FLOAT", "Infinity"},
      {"FLOAT", "abc"},
      {"BOOLEAN", "yes"},
      {"BOOLEAN", "TRUE"},
      {"DATE", "06/11/2026"},
      {"DATE", "2026-13-01"},
      {"TIME", "25:00"},
      {"DATE_TIME", "ontem"},
      {"DATE_TIME_OFFSET", "2026-11-06T10:00:00"},
      {"UUID", "nao-e-uuid"}
    };
    for (String[] pair : bad) {
      assertThatThrownBy(() -> bind(ColumnInfo.Kind.valueOf(pair[0]), pair[1]))
          .as(pair[0] + " " + pair[1])
          .isInstanceOf(ValueBinder.InvalidValueException.class);
    }
  }

  @Test
  void typesWithoutFilterSupportAreAConfigurationError() {
    for (ColumnInfo.Kind kind :
        new ColumnInfo.Kind[] {
          ColumnInfo.Kind.BINARY, ColumnInfo.Kind.XML, ColumnInfo.Kind.OTHER
        }) {
      assertThatThrownBy(() -> bind(kind, "x"))
          .isInstanceOf(ValueBinder.UnsupportedColumnException.class);
    }
  }

  @Test
  void invalidValueMessagesNeverEchoTheInput() {
    assertThatThrownBy(() -> bind(ColumnInfo.Kind.INTEGER, "'; DROP TABLE x;--"))
        .hasMessageNotContaining("DROP");
  }
}
