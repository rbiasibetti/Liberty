package br.com.liberty.domain.tenancy;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Identificador de um tenant: slug em minúsculas, usado em rotas, logs e chaves de cache. */
public record TenantId(String value) {

  private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

  /** Slugs que colidem com rotas da plataforma; o banco impõe a mesma lista (migração V2). */
  public static final Set<String> RESERVED =
      Set.of(
          "api",
          "oauth2",
          "actuator",
          "admin",
          "ws",
          "static",
          "assets",
          "login",
          "logout",
          "health",
          "error",
          "v1",
          "docs",
          "swagger-ui",
          "openapi",
          "platform");

  public TenantId {
    Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches() || RESERVED.contains(value)) {
      throw new IllegalArgumentException("Identificador de tenant inválido");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
