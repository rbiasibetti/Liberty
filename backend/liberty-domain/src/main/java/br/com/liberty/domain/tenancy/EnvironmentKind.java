package br.com.liberty.domain.tenancy;

/**
 * Ambiente de execução de um tenant: cada um tem conexão, clientes OAuth2 e publicação próprios.
 */
public enum EnvironmentKind {
  HML,
  PRD
}
