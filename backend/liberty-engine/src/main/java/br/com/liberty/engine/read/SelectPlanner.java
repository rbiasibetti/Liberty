package br.com.liberty.engine.read;

import br.com.liberty.engine.problem.ProblemException;
import br.com.liberty.engine.problem.ProblemException.Violation;
import br.com.liberty.infrastructure.catalog.ColumnInfo;
import br.com.liberty.infrastructure.catalog.SqlIdentifiers;
import br.com.liberty.infrastructure.catalog.TableInfo;
import br.com.liberty.snapshot.model.Select;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Monta o SELECT de uma integração de leitura. Colunas, filtros e ordenações vêm do snapshot e são
 * resolvidos no catálogo do tenant ({@link SqlIdentifiers}); o que o cliente envia só escolhe entre
 * o que o snapshot declarou e fornece valores, sempre como parâmetros. É puro: não toca no banco.
 */
public final class SelectPlanner {

  private static final int MAX_LIST_ITEMS = 100;

  private SelectPlanner() {}

  public static SelectPlan plan(Select select, TableInfo table, ParsedInput input) {
    List<Violation> violations = new ArrayList<>();
    Map<String, Object> params = new HashMap<>();
    int[] counter = {0};

    List<ColumnInfo> columns = new ArrayList<>();
    List<String> selected = new ArrayList<>();
    for (String name : select.columns()) {
      selected.add(SqlIdentifiers.column(table, name));
      columns.add(table.column(name).orElseThrow());
    }

    List<String> where = new ArrayList<>();
    for (Select.Filter filter : select.filters()) {
      String raw = input.values().get(filter.param());
      if (raw == null) {
        if (Boolean.TRUE.equals(filter.required())) {
          violations.add(new Violation(filter.param(), "required", "Filtro obrigatório"));
        }
        continue;
      }
      ColumnInfo column = table.column(filter.column()).orElseThrow();
      String left = SqlIdentifiers.column(table, filter.column());
      try {
        where.add(condition(filter, column, left, raw, params, counter));
      } catch (ValueBinder.InvalidValueException e) {
        violations.add(new Violation(filter.param(), "invalid_value", e.getMessage()));
      }
    }

    String orderBy = orderBy(select, table, input, violations);
    if (!violations.isEmpty()) {
      throw ProblemException.validation(violations);
    }

    boolean single = Boolean.TRUE.equals(select.single());
    int limit = single ? 1 : input.limit();
    params.put("offset", single ? 0 : input.offset());
    params.put("fetch", single ? 2 : limit + 1);
    String sql =
        "SELECT "
            + String.join(", ", selected)
            + " FROM "
            + SqlIdentifiers.table(table)
            + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
            + " ORDER BY "
            + orderBy
            + " OFFSET :offset ROWS FETCH NEXT :fetch ROWS ONLY";
    return new SelectPlan(sql, params, columns, limit, single ? 0 : input.offset(), single);
  }

  private static String condition(
      Select.Filter filter,
      ColumnInfo column,
      String left,
      String raw,
      Map<String, Object> params,
      int[] counter) {
    switch (filter.operator()) {
      case "=", "<>", ">", ">=", "<", "<=" -> {
        String name = next(counter);
        boolean upperBound = filter.operator().equals("<=") || filter.operator().equals(">");
        params.put(name, ValueBinder.bind(column, raw, upperBound));
        return left + " " + filter.operator() + " :" + name;
      }
      case "LIKE_PREFIX" -> {
        if (column.kind() != ColumnInfo.Kind.STRING) {
          throw new ValueBinder.UnsupportedColumnException("LIKE_PREFIX exige coluna de texto");
        }
        String name = next(counter);
        params.put(name, escapeLike(raw) + "%");
        return left + " LIKE :" + name + " ESCAPE '\\'";
      }
      case "IN" -> {
        List<String> items = split(raw, 1, MAX_LIST_ITEMS);
        List<String> placeholders = new ArrayList<>();
        for (String item : items) {
          String name = next(counter);
          params.put(name, ValueBinder.bind(column, item));
          placeholders.add(":" + name);
        }
        return left + " IN (" + String.join(", ", placeholders) + ")";
      }
      case "BETWEEN" -> {
        List<String> items = split(raw, 2, 2);
        String low = next(counter);
        String high = next(counter);
        params.put(low, ValueBinder.bind(column, items.get(0)));
        params.put(high, ValueBinder.bind(column, items.get(1), true));
        return left + " BETWEEN :" + low + " AND :" + high;
      }
      default -> throw new ValueBinder.UnsupportedColumnException("Operador sem suporte");
    }
  }

  private static String orderBy(
      Select select, TableInfo table, ParsedInput input, List<Violation> violations) {
    List<String> order = new ArrayList<>();
    Set<String> used = new HashSet<>();
    String requested = input.sort();
    if (requested != null) {
      boolean descending = requested.startsWith("-");
      String name = descending ? requested.substring(1) : requested;
      String allowed =
          select.sortable().stream().filter(c -> c.equalsIgnoreCase(name)).findFirst().orElse(null);
      if (allowed == null) {
        violations.add(
            new Violation("sort", "sort_not_allowed", "Coluna não ordenável nesta integração"));
      } else {
        add(order, used, table, allowed, descending ? "DESC" : "ASC");
      }
    } else if (select.defaultSort() != null) {
      String direction = "DESC".equals(select.defaultSort().direction()) ? "DESC" : "ASC";
      add(order, used, table, select.defaultSort().column(), direction);
    }
    // desempate estável: a chave primária (ou, sem ela, todas as colunas retornadas), para a
    // paginação não repetir nem pular linhas
    for (ColumnInfo key : table.primaryKey()) {
      add(order, used, table, key.name(), "ASC");
    }
    if (table.primaryKey().isEmpty()) {
      for (String name : select.columns()) {
        add(order, used, table, name, "ASC");
      }
    }
    return String.join(", ", order);
  }

  private static void add(
      List<String> order, Set<String> used, TableInfo table, String name, String direction) {
    String column = SqlIdentifiers.column(table, name);
    if (used.add(column.toLowerCase(Locale.ROOT))) {
      order.add(column + " " + direction);
    }
  }

  private static List<String> split(String raw, int min, int max) {
    List<String> items = new ArrayList<>();
    for (String item : raw.split(",", -1)) {
      String trimmed = item.trim();
      if (trimmed.isEmpty()) {
        throw new ValueBinder.InvalidValueException("Lista com item vazio");
      }
      items.add(trimmed);
    }
    if (items.size() < min || items.size() > max) {
      throw new ValueBinder.InvalidValueException(
          min == max
              ? "Informe exatamente " + min + " valores separados por vírgula"
              : "Informe de " + min + " a " + max + " valores separados por vírgula");
    }
    return items;
  }

  private static String escapeLike(String raw) {
    StringBuilder out = new StringBuilder();
    for (char c : raw.toCharArray()) {
      if (c == '\\' || c == '%' || c == '_' || c == '[') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  private static String next(int[] counter) {
    return "p" + counter[0]++;
  }
}
