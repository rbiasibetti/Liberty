-- Schema do banco central (liberty_core). Metadados da plataforma; os dados de negócio ficam nos bancos dos tenants.
-- Convenções: prefixo int_, datas em UTC (DATETIME2), nenhuma FK com ON DELETE CASCADE (nada é apagado, só arquivado).

CREATE TABLE int_tenant (
    id          BIGINT IDENTITY PRIMARY KEY,
    slug        VARCHAR(63)   NOT NULL,
    name        NVARCHAR(200) NOT NULL,
    plan_code   VARCHAR(30)   NOT NULL DEFAULT 'STANDARD',
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_int_tenant_slug UNIQUE (slug),
    CONSTRAINT CK_int_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    -- mesmo formato de TenantId: minúsculas, dígitos e hífen, 2 a 63 caracteres, sem hífen inicial
    CONSTRAINT CK_int_tenant_slug CHECK (
        LEN(slug) BETWEEN 2 AND 63
        AND slug COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%'
        AND slug NOT LIKE '-%')
);
GO

CREATE TABLE int_tenant_connection (
    id           BIGINT IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES int_tenant(id),
    environment  VARCHAR(3)    NOT NULL,
    jdbc_url     VARCHAR(500)  NOT NULL,
    db_username  VARCHAR(128)  NOT NULL,
    secret_ref   VARCHAR(300)  NOT NULL,   -- caminho no cofre; a senha nunca é guardada aqui
    pool_max     INT           NOT NULL DEFAULT 5,
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_int_tenant_connection UNIQUE (tenant_id, environment),
    CONSTRAINT CK_int_tenant_connection_env CHECK (environment IN ('HML', 'PRD')),
    CONSTRAINT CK_int_tenant_connection_pool CHECK (pool_max BETWEEN 1 AND 50),
    CONSTRAINT CK_int_tenant_connection_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
GO

CREATE TABLE int_user_role (
    user_sub    VARCHAR(200)  NOT NULL,   -- claim "sub" do provedor OIDC
    tenant_id   BIGINT        NOT NULL REFERENCES int_tenant(id),
    role        VARCHAR(20)   NOT NULL,
    created_at  DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_int_user_role PRIMARY KEY (user_sub, tenant_id, role),
    CONSTRAINT CK_int_user_role CHECK (role IN ('VIEWER', 'INTEGRATOR', 'PUBLISHER'))
);
GO

CREATE TABLE int_api_client (
    id           BIGINT IDENTITY PRIMARY KEY,
    tenant_id    BIGINT         NOT NULL REFERENCES int_tenant(id),
    client_id    VARCHAR(100)   NOT NULL,
    secret_hash  VARCHAR(200)   NOT NULL,   -- bcrypt ou Argon2, nunca o segredo
    environment  VARCHAR(3)     NOT NULL,
    scopes       NVARCHAR(1000) NOT NULL,   -- separados por espaço: integration:execute:<chave>
    status       VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at   DATETIME2(3)   NULL,
    revoked_at   DATETIME2(3)   NULL,
    CONSTRAINT UQ_int_api_client UNIQUE (client_id),
    CONSTRAINT CK_int_api_client_env CHECK (environment IN ('HML', 'PRD')),
    CONSTRAINT CK_int_api_client_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);
GO

CREATE TABLE int_integration (
    id               BIGINT IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES int_tenant(id),
    integration_key  VARCHAR(100)  NOT NULL,
    name             NVARCHAR(255) NOT NULL,
    http_method      VARCHAR(10)   NOT NULL,
    route_pattern    VARCHAR(400)  NOT NULL,   -- ex.: /pedidos/{numero}
    archived         BIT           NOT NULL DEFAULT 0,
    created_at       DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_int_integration_key   UNIQUE (tenant_id, integration_key),
    CONSTRAINT UQ_int_integration_route UNIQUE (tenant_id, http_method, route_pattern),
    CONSTRAINT CK_int_integration_method CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH')),
    CONSTRAINT CK_int_integration_key CHECK (integration_key COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%')
);
GO

CREATE TABLE int_integration_version (
    id               BIGINT IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES int_tenant(id),
    integration_id   BIGINT         NOT NULL REFERENCES int_integration(id),
    version_no       INT            NOT NULL,
    state            VARCHAR(20)    NOT NULL,
    snapshot         NVARCHAR(MAX)  NOT NULL,
    snapshot_sha256  BINARY(32)     NOT NULL,
    author_sub       VARCHAR(200)   NOT NULL,
    reviewer_sub     VARCHAR(200)   NULL,
    review_comment   NVARCHAR(2000) NULL,
    created_at       DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    reviewed_at      DATETIME2(3)   NULL,
    CONSTRAINT UQ_int_version_no UNIQUE (integration_id, version_no),
    CONSTRAINT CK_int_version_state CHECK (state IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT CK_int_version_snapshot CHECK (ISJSON(snapshot) = 1),
    CONSTRAINT CK_int_version_reviewer CHECK (reviewer_sub IS NULL OR reviewer_sub <> author_sub)
);
CREATE UNIQUE INDEX UX_int_version_one_draft ON int_integration_version (integration_id) WHERE state = 'DRAFT';
GO

-- Versões saem do rascunho uma única vez e depois ficam imutáveis; nenhuma é apagada.
CREATE TRIGGER trg_int_version_guard ON int_integration_version AFTER UPDATE, DELETE AS
BEGIN
    SET NOCOUNT ON;
    IF NOT EXISTS (SELECT 1 FROM inserted)
    BEGIN
        IF EXISTS (SELECT 1 FROM deleted WHERE state <> 'DRAFT')
            THROW 50001, 'Versoes que sairam do rascunho nao podem ser apagadas', 1;
        RETURN;
    END;
    IF EXISTS (
        SELECT 1 FROM inserted i JOIN deleted d ON d.id = i.id
        WHERE d.state <> 'DRAFT'
          AND (i.snapshot <> d.snapshot OR i.snapshot_sha256 <> d.snapshot_sha256
               OR i.version_no <> d.version_no OR i.integration_id <> d.integration_id
               OR i.tenant_id <> d.tenant_id OR i.author_sub <> d.author_sub))
        THROW 50002, 'O conteudo de uma versao fora do rascunho e imutavel', 1;
    IF EXISTS (
        SELECT 1 FROM inserted i JOIN deleted d ON d.id = i.id
        WHERE i.state <> d.state
          AND NOT ((d.state = 'DRAFT' AND i.state = 'IN_REVIEW')
                OR (d.state = 'IN_REVIEW' AND i.state IN ('PUBLISHED', 'REJECTED'))))
        THROW 50003, 'Transicao de estado de versao invalida', 1;
END;
GO

CREATE TABLE int_publication (
    id              BIGINT IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES int_tenant(id),
    integration_id  BIGINT        NOT NULL REFERENCES int_integration(id),
    version_id      BIGINT        NOT NULL REFERENCES int_integration_version(id),
    environment     VARCHAR(3)    NOT NULL,
    is_active       BIT           NOT NULL,
    published_by    VARCHAR(200)  NOT NULL,
    published_at    DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT CK_int_publication_env CHECK (environment IN ('HML', 'PRD'))
);
CREATE UNIQUE INDEX UX_int_publication_active ON int_publication (integration_id, environment) WHERE is_active = 1;
CREATE INDEX IX_int_publication_tenant_env ON int_publication (tenant_id, environment, is_active) INCLUDE (integration_id, version_id);
GO

CREATE TABLE int_idempotency (
    tenant_id       BIGINT        NOT NULL REFERENCES int_tenant(id),
    integration_id  BIGINT        NOT NULL REFERENCES int_integration(id),
    idem_key        VARCHAR(200)  NOT NULL,
    request_sha256  BINARY(32)    NOT NULL,
    status          VARCHAR(12)   NOT NULL,
    http_status     SMALLINT      NULL,
    response        NVARCHAR(MAX) NULL,
    created_at      DATETIME2(3)  NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at      DATETIME2(3)  NOT NULL,
    CONSTRAINT PK_int_idempotency PRIMARY KEY (tenant_id, integration_id, idem_key),
    CONSTRAINT CK_int_idempotency_status CHECK (status IN ('IN_PROGRESS', 'DONE'))
);
CREATE INDEX IX_int_idempotency_expires ON int_idempotency (expires_at);
GO

-- Auditoria: sem FK (o histórico sobrevive a qualquer mudança de metadados). A chave clusterizada começa por
-- created_at para que a E17 possa particioná-la por mês (CREATE CLUSTERED INDEX ... DROP_EXISTING ON esquema).
CREATE TABLE int_audit_execution (
    id                  BIGINT IDENTITY,
    created_at          DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    tenant_id           BIGINT         NOT NULL,
    integration_key     VARCHAR(100)   NOT NULL,
    integration_id      BIGINT         NULL,
    version_id          BIGINT         NULL,
    environment         VARCHAR(3)     NOT NULL,
    http_method         VARCHAR(10)    NOT NULL,
    route               VARCHAR(400)   NOT NULL,
    status_code         SMALLINT       NOT NULL,
    duration_ms         INT            NOT NULL,
    db_duration_ms      INT            NULL,
    script_duration_ms  INT            NULL,
    error_code          VARCHAR(50)    NULL,
    error_message       NVARCHAR(1000) NULL,
    request_id          VARCHAR(64)    NOT NULL,
    client_id           VARCHAR(100)   NULL,
    CONSTRAINT PK_int_audit_execution PRIMARY KEY CLUSTERED (created_at, id)
);
CREATE INDEX IX_int_audit_tenant_date ON int_audit_execution (tenant_id, created_at DESC)
    INCLUDE (integration_key, status_code, duration_ms);
CREATE INDEX IX_int_audit_request ON int_audit_execution (request_id);
GO

CREATE TABLE int_audit_payload (
    created_at       DATETIME2(3)   NOT NULL,
    execution_id     BIGINT         NOT NULL,
    request_payload  VARBINARY(MAX) NULL,   -- JSON mascarado, comprimido com COMPRESS()
    executed_sql     VARBINARY(MAX) NULL,
    CONSTRAINT PK_int_audit_payload PRIMARY KEY CLUSTERED (created_at, execution_id),
    CONSTRAINT FK_int_audit_payload_execution FOREIGN KEY (created_at, execution_id)
        REFERENCES int_audit_execution (created_at, id)
);
GO

CREATE TABLE int_export_job (
    id             BIGINT IDENTITY PRIMARY KEY,
    tenant_id      BIGINT         NOT NULL REFERENCES int_tenant(id),
    requested_by   VARCHAR(200)   NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    format         VARCHAR(10)    NOT NULL,
    filter_json    NVARCHAR(MAX)  NOT NULL,
    object_key     VARCHAR(500)   NULL,
    error_message  NVARCHAR(1000) NULL,
    created_at     DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at     DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at     DATETIME2(3)   NULL,
    CONSTRAINT CK_int_export_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT CK_int_export_job_format CHECK (format IN ('CSV', 'XLSX')),
    CONSTRAINT CK_int_export_job_filter CHECK (ISJSON(filter_json) = 1)
);
CREATE INDEX IX_int_export_job_tenant ON int_export_job (tenant_id, created_at DESC);
GO
