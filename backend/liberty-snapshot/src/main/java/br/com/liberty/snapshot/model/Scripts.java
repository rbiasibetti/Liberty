package br.com.liberty.snapshot.model;

/**
 * Ganchos de script JavaScript opcionais, executados em processo trabalhador isolado (ADR 0017).
 */
public record Scripts(String beforeExecute, String afterExecute) {}
