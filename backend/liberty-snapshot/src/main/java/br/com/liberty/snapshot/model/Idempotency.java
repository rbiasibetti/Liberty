package br.com.liberty.snapshot.model;

/** Se verdadeiro, escritas exigem o header Idempotency-Key. */
public record Idempotency(boolean required) {}
