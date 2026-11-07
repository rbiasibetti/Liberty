-- Slugs que colidem com rotas da própria plataforma (/{slug}/api/..., /api/..., /oauth2/...) não podem ser tenants.
ALTER TABLE int_tenant ADD CONSTRAINT CK_int_tenant_slug_reserved CHECK (
    slug NOT IN ('api', 'oauth2', 'actuator', 'admin', 'ws', 'static', 'assets', 'login', 'logout',
                 'health', 'error', 'v1', 'docs', 'swagger-ui', 'openapi', 'platform'));
GO
