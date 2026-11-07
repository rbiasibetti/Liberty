package br.com.liberty.snapshot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

/**
 * Forma canônica do JSON (estilo RFC 8785): chaves de objeto em ordem lexicográfica, sem espaços,
 * números sem zeros à direita nem notação científica, strings com escape mínimo. Dois documentos
 * logicamente iguais têm o mesmo texto e, portanto, o mesmo SHA-256, qualquer que seja a ordem das
 * chaves ou a formatação.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  public static String write(JsonNode node) {
    StringBuilder out = new StringBuilder();
    append(node, out);
    return out.toString();
  }

  public static byte[] sha256(JsonNode node) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(write(node).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void append(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(Map.Entry.comparingByKey());
      out.append('{');
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        out.append(quote(entries.get(i).getKey())).append(':');
        append(entries.get(i).getValue(), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        append(node.get(i), out);
      }
      out.append(']');
    } else if (node.isString()) {
      out.append(quote(node.asString()));
    } else if (node.isNumber()) {
      out.append(number(node.decimalValue()));
    } else if (node.isBoolean()) {
      out.append(node.asBoolean());
    } else {
      out.append("null");
    }
  }

  private static String number(BigDecimal value) {
    if (value.signum() == 0) {
      return "0";
    }
    return value.stripTrailingZeros().toPlainString();
  }

  private static String quote(String text) {
    return StringNode.valueOf(text).toString();
  }
}
