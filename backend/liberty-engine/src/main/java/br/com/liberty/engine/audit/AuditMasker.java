package br.com.liberty.engine.audit;

import java.util.regex.Pattern;

/**
 * Mascara o que possa ser dado pessoal ou segredo antes de gravar texto na auditoria: e-mails,
 * sequências longas de dígitos (documentos, cartões, telefones), cabeçalhos de autorização e tokens
 * longos. O texto também é limitado em tamanho.
 */
public final class AuditMasker {

  static final int MAX_LENGTH = 300;
  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
  private static final Pattern BEARER =
      Pattern.compile("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern DIGITS = Pattern.compile("\\d[\\d.\\-/ ]{4,}\\d");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,}");

  private AuditMasker() {}

  public static String mask(String text) {
    if (text == null) {
      return null;
    }
    String masked = BEARER.matcher(text).replaceAll("$1 ***");
    masked = EMAIL.matcher(masked).replaceAll("***@***");
    masked = DIGITS.matcher(masked).replaceAll("***");
    masked = TOKEN.matcher(masked).replaceAll("***");
    masked = masked.replaceAll("[\\p{Cntrl}]", " ");
    return masked.length() > MAX_LENGTH ? masked.substring(0, MAX_LENGTH) : masked;
  }
}
