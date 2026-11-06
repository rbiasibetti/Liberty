-- Schema de negócio de exemplo (pertence ao "cliente"; a plataforma nunca o altera).
-- Executado em cada banco de tenant. ${TENANT_LOGIN} = login da aplicação naquele tenant.
IF OBJECT_ID('dbo.tb_cliente') IS NULL
CREATE TABLE dbo.tb_cliente (
  id             INT IDENTITY PRIMARY KEY,
  nr_documento   VARCHAR(14)    NOT NULL UNIQUE,
  nm_cliente     NVARCHAR(200)  NOT NULL,
  dt_criacao     DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME()
);
GO
IF OBJECT_ID('dbo.tb_pedido') IS NULL
CREATE TABLE dbo.tb_pedido (
  id             BIGINT IDENTITY PRIMARY KEY,
  nr_pedido      VARCHAR(30)    NOT NULL UNIQUE,
  cliente_id     INT            NOT NULL REFERENCES dbo.tb_cliente(id),
  dt_pedido      DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME(),
  vl_total       DECIMAL(18,2)  NOT NULL
);
GO
IF OBJECT_ID('dbo.tb_pedido_item') IS NULL
CREATE TABLE dbo.tb_pedido_item (
  id             BIGINT IDENTITY PRIMARY KEY,
  pedido_id      BIGINT         NOT NULL REFERENCES dbo.tb_pedido(id),
  cd_produto     VARCHAR(30)    NOT NULL,
  qt_vendida     DECIMAL(18,3)  NOT NULL CHECK (qt_vendida > 0),
  vl_unitario    DECIMAL(18,4)  NOT NULL
);
GO
-- Tabela com trigger: cobre o caso de OUTPUT ... INTO exigido pelo motor de escrita (E12).
IF OBJECT_ID('dbo.tb_pedido_log') IS NULL
CREATE TABLE dbo.tb_pedido_log (
  id             BIGINT IDENTITY PRIMARY KEY,
  pedido_id      BIGINT         NOT NULL,
  dt_log         DATETIME2(3)   NOT NULL DEFAULT SYSUTCDATETIME()
);
GO
IF OBJECT_ID('dbo.trg_pedido_log') IS NULL
EXEC('CREATE TRIGGER dbo.trg_pedido_log ON dbo.tb_pedido AFTER INSERT AS
      INSERT INTO dbo.tb_pedido_log (pedido_id) SELECT id FROM inserted;');
GO
IF NOT EXISTS (SELECT 1 FROM dbo.tb_cliente WHERE nr_documento = '11144477735')
  INSERT INTO dbo.tb_cliente (nr_documento, nm_cliente) VALUES ('11144477735', N'Cliente de Exemplo');
GO
-- Privilégio mínimo da aplicação: sem DELETE, sem DDL.
IF USER_ID('${TENANT_LOGIN}') IS NULL CREATE USER ${TENANT_LOGIN} FOR LOGIN ${TENANT_LOGIN};
GRANT SELECT, INSERT, UPDATE ON SCHEMA::dbo TO ${TENANT_LOGIN};
GRANT VIEW DEFINITION ON SCHEMA::dbo TO ${TENANT_LOGIN};
GO
