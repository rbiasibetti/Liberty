-- E24: integrações DELETE (remoção por chave completa, com aprovação reforçada na publicação).
ALTER TABLE int_integration DROP CONSTRAINT CK_int_integration_method;
GO
ALTER TABLE int_integration ADD CONSTRAINT CK_int_integration_method
    CHECK (http_method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE'));
GO
