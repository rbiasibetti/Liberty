package br.com.liberty.domain.tenancy;

/** Papéis de usuário dentro de um tenant. O administrador da plataforma vem do provedor OIDC. */
public enum TenantRole {
  VIEWER,
  INTEGRATOR,
  PUBLISHER
}
