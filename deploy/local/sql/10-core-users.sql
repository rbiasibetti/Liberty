-- Executado em liberty_core: migrador com DDL (Flyway) e aplicação só com DML.
IF USER_ID('liberty_core_migrator') IS NULL CREATE USER liberty_core_migrator FOR LOGIN liberty_core_migrator;
ALTER ROLE db_owner ADD MEMBER liberty_core_migrator;
GO
IF USER_ID('liberty_core_app') IS NULL CREATE USER liberty_core_app FOR LOGIN liberty_core_app;
ALTER ROLE db_datareader ADD MEMBER liberty_core_app;
ALTER ROLE db_datawriter ADD MEMBER liberty_core_app;
GO
