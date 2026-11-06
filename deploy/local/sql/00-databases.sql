-- Bancos de desenvolvimento: o core (vazio, migrado pelo Flyway na E06) e dois tenants de exemplo.
-- Executado no banco master. Variáveis ${...} vêm do ambiente; todas as instruções são idempotentes.
IF DB_ID('liberty_core') IS NULL CREATE DATABASE liberty_core COLLATE Latin1_General_100_CI_AS_SC_UTF8;
GO
IF DB_ID('tenant_cliente_a') IS NULL CREATE DATABASE tenant_cliente_a COLLATE Latin1_General_100_CI_AS_SC_UTF8;
GO
IF DB_ID('tenant_cliente_b') IS NULL CREATE DATABASE tenant_cliente_b COLLATE Latin1_General_100_CI_AS_SC_UTF8;
GO
IF SUSER_ID('liberty_core_migrator') IS NULL CREATE LOGIN liberty_core_migrator WITH PASSWORD = '${LIBERTY_CORE_MIGRATOR_PASSWORD}', CHECK_POLICY = OFF;
GO
IF SUSER_ID('liberty_core_app') IS NULL CREATE LOGIN liberty_core_app WITH PASSWORD = '${LIBERTY_CORE_APP_PASSWORD}', CHECK_POLICY = OFF;
GO
IF SUSER_ID('liberty_app_cliente_a') IS NULL CREATE LOGIN liberty_app_cliente_a WITH PASSWORD = '${LIBERTY_TENANT_A_DB_PASSWORD}', CHECK_POLICY = OFF;
GO
IF SUSER_ID('liberty_app_cliente_b') IS NULL CREATE LOGIN liberty_app_cliente_b WITH PASSWORD = '${LIBERTY_TENANT_B_DB_PASSWORD}', CHECK_POLICY = OFF;
GO
