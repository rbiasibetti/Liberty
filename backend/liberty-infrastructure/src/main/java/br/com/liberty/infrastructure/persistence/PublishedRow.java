package br.com.liberty.infrastructure.persistence;

/** Uma publicação ativa com os dados da integração e o snapshot da versão publicada. */
public record PublishedRow(
    Long publicationId,
    Long integrationId,
    String integrationKey,
    String httpMethod,
    String routePattern,
    Long versionId,
    Integer versionNo,
    String snapshot) {}
