package br.com.liberty.snapshot.model;

/**
 * Resposta de sucesso; o status padrão depende da operação (200 para leitura, 201 para criação).
 */
public record Response(Integer successStatus) {}
