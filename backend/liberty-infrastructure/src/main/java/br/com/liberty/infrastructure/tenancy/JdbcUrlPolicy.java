package br.com.liberty.infrastructure.tenancy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URLs de conexão vêm de cadastro administrativo, mas são validadas de novo antes de abrir um pool:
 * só SQL Server, com criptografia, e sem propriedades do driver que carregam código ou arquivos.
 */
final class JdbcUrlPolicy {

  private static final Pattern SHAPE =
      Pattern.compile("jdbc:sqlserver://[A-Za-z0-9._-]+(:\\d{1,5})?(;[A-Za-z]+=[^;=]*)*;?");

  private static final Set<String> FORBIDDEN =
      Set.of(
          "socketfactoryclass",
          "socketfactoryconstructorarg",
          "accesstokencallbackclass",
          "keystorelocation",
          "keystoresecret",
          "keystoreauthentication",
          "truststore",
          "truststorepassword",
          "clientcertificate",
          "clientkey",
          "clientkeypassword",
          "jaasconfigurationname",
          "gsscredential",
          "authenticationscheme",
          "serverspn",
          "failoverpartner",
          "resourcemanager");

  private JdbcUrlPolicy() {}

  static void check(String url) {
    if (url == null || !SHAPE.matcher(url).matches()) {
      throw new TenantUnavailableException("URL de conexão do tenant inválida");
    }
    String lower = url.toLowerCase(Locale.ROOT);
    boolean encrypted = lower.contains(";encrypt=true") || lower.contains(";encrypt=strict");
    if (!encrypted) {
      throw new TenantUnavailableException("A conexão do tenant exige encrypt=true");
    }
    for (String property : lower.substring(lower.indexOf(';') + 1).split(";")) {
      String name =
          property.contains("=") ? property.substring(0, property.indexOf('=')) : property;
      if (FORBIDDEN.contains(name.trim())) {
        throw new TenantUnavailableException("Propriedade proibida na URL de conexão do tenant");
      }
    }
  }
}
