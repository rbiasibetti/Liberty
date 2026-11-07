-- E22: hosts que os scripts de um tenant podem chamar com http.fetch (lista de permissão por tenant).
-- Entrada = nome DNS em minúsculas, com ":porta" quando não for a padrão (443). Nunca IP literal.
CREATE TABLE int_tenant_http_host (
    tenant_id   BIGINT        NOT NULL REFERENCES int_tenant(id),
    host        VARCHAR(260)  NOT NULL,
    created_by  VARCHAR(200)  NOT NULL,
    created_at  DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_int_tenant_http_host PRIMARY KEY (tenant_id, host),
    CONSTRAINT CK_int_tenant_http_host CHECK (
        host COLLATE Latin1_General_BIN2 = LOWER(host) COLLATE Latin1_General_BIN2
        AND host COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9.:-]%'
        AND host NOT LIKE '.%' AND host NOT LIKE '%.'
        AND host NOT LIKE '[0-9]%.[0-9]%.[0-9]%.[0-9]%'   -- sem IP v4 literal (a aplicação também recusa)
    )
);
GO
