-- E23: fila durável por tenant no banco central. A mensagem leva o tenant e o ambiente (o
-- envelope); consumidores a reivindicam com READPAST (sem bloqueio entre si), com visibilidade por
-- arrendamento (locked_until), reprocessamento com recuo e fila de mensagens mortas (DEAD).
CREATE TABLE int_queue_message (
    id               BIGINT IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES int_tenant(id),
    environment      VARCHAR(3)     NOT NULL,
    queue_name       VARCHAR(50)    NOT NULL,
    payload          NVARCHAR(MAX)  NOT NULL,
    correlation_id   VARCHAR(100)   NULL,
    status           VARCHAR(12)    NOT NULL DEFAULT 'READY',
    attempts         INT            NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    locked_until     DATETIME2(3)   NULL,
    last_error       VARCHAR(200)   NULL,
    created_at       DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
    died_at          DATETIME2(3)   NULL,
    CONSTRAINT CK_int_queue_status CHECK (status IN ('READY', 'PROCESSING', 'DEAD')),
    CONSTRAINT CK_int_queue_env CHECK (environment IN ('HML', 'PRD')),
    CONSTRAINT CK_int_queue_name CHECK (
        queue_name COLLATE Latin1_General_BIN2 NOT LIKE '%[^a-z0-9-]%' AND LEN(queue_name) >= 1),
    CONSTRAINT CK_int_queue_payload CHECK (ISJSON(payload) = 1)
);
GO

CREATE INDEX IX_int_queue_ready ON int_queue_message (tenant_id, next_attempt_at, id)
    WHERE status = 'READY';
CREATE INDEX IX_int_queue_leases ON int_queue_message (locked_until)
    WHERE status = 'PROCESSING';
CREATE INDEX IX_int_queue_dead ON int_queue_message (tenant_id, died_at)
    WHERE status = 'DEAD';
GO
