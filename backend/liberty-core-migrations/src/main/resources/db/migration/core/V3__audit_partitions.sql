-- E17: partições mensais da auditoria e rotina de manutenção (partições futuras e arquivamento).
-- A rotina roda com os direitos do dono (EXECUTE AS OWNER): o usuário da aplicação não recebe DDL.

-- A chave estrangeira do payload impede a troca de partições; o histórico de auditoria não tem FK
-- (como diz o V1) e o payload é purgado pela mesma retenção, na rotina abaixo.
ALTER TABLE int_audit_payload DROP CONSTRAINT FK_int_audit_payload_execution;
GO

DECLARE @first DATE = DATEADD(MONTH, -14, DATEFROMPARTS(YEAR(SYSUTCDATETIME()), MONTH(SYSUTCDATETIME()), 1));
DECLARE @sql NVARCHAR(MAX) = N'CREATE PARTITION FUNCTION pf_audit_month (DATETIME2(3)) AS RANGE RIGHT FOR VALUES (';
DECLARE @i INT = 0;
WHILE @i <= 38
BEGIN
    SET @sql += CASE WHEN @i = 0 THEN N'' ELSE N',' END
              + N'''' + CONVERT(NVARCHAR(10), DATEADD(MONTH, @i, @first), 23) + N'''';
    SET @i += 1;
END;
SET @sql += N')';
EXEC sp_executesql @sql;
GO

CREATE PARTITION SCHEME ps_audit_month AS PARTITION pf_audit_month ALL TO ([PRIMARY]);
GO

-- Todos os índices ficam alinhados à partição (exigência para trocar partições de tabela).
CREATE UNIQUE CLUSTERED INDEX PK_int_audit_execution ON int_audit_execution (created_at, id)
    WITH (DROP_EXISTING = ON) ON ps_audit_month (created_at);
CREATE INDEX IX_int_audit_tenant_date ON int_audit_execution (tenant_id, created_at DESC)
    INCLUDE (integration_key, status_code, duration_ms)
    WITH (DROP_EXISTING = ON) ON ps_audit_month (created_at);
CREATE INDEX IX_int_audit_request ON int_audit_execution (request_id)
    WITH (DROP_EXISTING = ON) ON ps_audit_month (created_at);
GO

CREATE PROCEDURE dbo.sp_int_audit_maintain @monthsAhead INT = 3, @retentionMonths INT = 13
WITH EXECUTE AS OWNER
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @lock INT;
    EXEC @lock = sp_getapplock @Resource = 'int_audit_maintain', @LockMode = 'Exclusive',
                               @LockOwner = 'Session', @LockTimeout = 0;
    IF @lock < 0 RETURN; -- outra instância já está cuidando disso

    BEGIN TRY
        DECLARE @month DATE = DATEFROMPARTS(YEAR(SYSUTCDATETIME()), MONTH(SYSUTCDATETIME()), 1);
        DECLARE @i INT = 0, @boundary DATETIME2(3);

        -- 1) garante as partições dos próximos meses
        WHILE @i <= @monthsAhead
        BEGIN
            SET @boundary = DATEADD(MONTH, @i, @month);
            IF NOT EXISTS (SELECT 1 FROM sys.partition_range_values v
                           JOIN sys.partition_functions f ON f.function_id = v.function_id
                           WHERE f.name = 'pf_audit_month' AND CONVERT(DATETIME2(3), v.value) = @boundary)
            BEGIN
                ALTER PARTITION SCHEME ps_audit_month NEXT USED [PRIMARY];
                ALTER PARTITION FUNCTION pf_audit_month() SPLIT RANGE (@boundary);
            END;
            SET @i += 1;
        END;

        -- 2) arquiva, em tabelas próprias, as partições anteriores à retenção que ainda têm linhas
        DECLARE @cutoff DATETIME2(3) = DATEADD(MONTH, -@retentionMonths, @month);
        DECLARE @part INT, @upper DATETIME2(3), @name SYSNAME, @sql NVARCHAR(MAX), @suffix INT;
        WHILE 1 = 1
        BEGIN
            SET @part = NULL;
            SELECT TOP 1 @part = p.partition_number, @upper = CONVERT(DATETIME2(3), v.value)
            FROM sys.partitions p
            JOIN sys.partition_functions f ON f.name = 'pf_audit_month'
            JOIN sys.partition_range_values v ON v.function_id = f.function_id AND v.boundary_id = p.partition_number
            WHERE p.object_id = OBJECT_ID('dbo.int_audit_execution') AND p.index_id = 1 AND p.rows > 0
              AND CONVERT(DATETIME2(3), v.value) <= @cutoff
            ORDER BY p.partition_number;
            IF @part IS NULL BREAK;

            SET @suffix = 0;
            SET @name = 'int_audit_archive_' + CONVERT(NVARCHAR(8), @upper, 112);
            WHILE OBJECT_ID('dbo.' + @name) IS NOT NULL
            BEGIN
                SET @suffix += 1;
                SET @name = 'int_audit_archive_' + CONVERT(NVARCHAR(8), @upper, 112) + '_' + CAST(@suffix AS NVARCHAR(5));
            END;

            SET @sql = N'CREATE TABLE dbo.' + QUOTENAME(@name) + N' (
                id BIGINT IDENTITY, created_at DATETIME2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
                tenant_id BIGINT NOT NULL, integration_key VARCHAR(100) NOT NULL, integration_id BIGINT NULL,
                version_id BIGINT NULL, environment VARCHAR(3) NOT NULL, http_method VARCHAR(10) NOT NULL,
                route VARCHAR(400) NOT NULL, status_code SMALLINT NOT NULL, duration_ms INT NOT NULL,
                db_duration_ms INT NULL, script_duration_ms INT NULL, error_code VARCHAR(50) NULL,
                error_message NVARCHAR(1000) NULL, request_id VARCHAR(64) NOT NULL, client_id VARCHAR(100) NULL,
                CONSTRAINT ' + QUOTENAME('PK_' + @name) + N' PRIMARY KEY CLUSTERED (created_at, id));
                CREATE INDEX ' + QUOTENAME('IXT_' + @name) + N' ON dbo.' + QUOTENAME(@name) + N' (tenant_id, created_at DESC)
                    INCLUDE (integration_key, status_code, duration_ms);
                CREATE INDEX ' + QUOTENAME('IXR_' + @name) + N' ON dbo.' + QUOTENAME(@name) + N' (request_id);
                ALTER TABLE dbo.int_audit_execution SWITCH PARTITION ' + CAST(@part AS NVARCHAR(10))
                + N' TO dbo.' + QUOTENAME(@name) + N';';
            EXEC sp_executesql @sql;

            -- o payload amostrado dessas execuções segue a mesma retenção
            DELETE FROM dbo.int_audit_payload WHERE created_at < @upper;

            -- a partição ficou vazia: funde com a seguinte (só metadados)
            ALTER PARTITION FUNCTION pf_audit_month() MERGE RANGE (@upper);
        END;
    END TRY
    BEGIN CATCH
        EXEC sp_releaseapplock @Resource = 'int_audit_maintain', @LockOwner = 'Session';
        THROW;
    END CATCH;
    EXEC sp_releaseapplock @Resource = 'int_audit_maintain', @LockOwner = 'Session';
END;
GO

IF DATABASE_PRINCIPAL_ID('liberty_core_app') IS NOT NULL
    GRANT EXECUTE ON dbo.sp_int_audit_maintain TO liberty_core_app;
GO
