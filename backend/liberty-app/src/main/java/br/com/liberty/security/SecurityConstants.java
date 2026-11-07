package br.com.liberty.security;

/** Nomes de claims e papéis usados em toda a camada de segurança. */
public final class SecurityConstants {

  public static final String CLAIM_TENANT = "tenant_id";
  public static final String CLAIM_ENV = "env";
  public static final String CLAIM_CLIENT_ID = "client_id";
  public static final String ROLE_CLIENT = "ROLE_CLIENT";
  public static final String ROLE_USER = "ROLE_USER";
  public static final String ROLE_PLATFORM_ADMIN = "ROLE_PLATFORM_ADMIN";

  private SecurityConstants() {}
}
