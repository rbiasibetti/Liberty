package br.com.liberty.snapshot.model;

/** Identidade e rota da integração: {@code route} usa variáveis {@code {nome}}. */
public record Integration(String key, String name, String method, String route) {}
