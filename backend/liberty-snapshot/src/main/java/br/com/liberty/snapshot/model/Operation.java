package br.com.liberty.snapshot.model;

/**
 * O que a integração faz: leitura ({@code select}) ou escrita ({@code write}); só um deles existe.
 */
public record Operation(String type, Select select, Write write) {}
