package br.com.liberty.domain.tenancy;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificador de um tenant: slug em minúsculas, usado em rotas, logs e chaves de cache. */
public record TenantId(String value) {

  private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

  public TenantId {
    Objects.requireNonNull(value, "value");
    if (!FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("Identificador de tenant inválido");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
