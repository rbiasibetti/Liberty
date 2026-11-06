# 0019. Persistência do banco central

- Status: aceito
- Data: 2026-11-06
- Etapa: E06

## Contexto

O core guarda metadados, versões, publicações, idempotência e auditoria (ADR 0002, 0006, 0010). A E06 criou o schema, o mapeamento JPA e o acesso a dados.

## Decisão

- **Migrações em módulo próprio** (`liberty-core-migrations`, sem dependências): a esteira de deploy roda `mvnw -pl liberty-core-migrations flyway:migrate` com o usuário migrador (`db_owner`), e a aplicação, com o usuário só de DML, nunca executa DDL. Na aplicação `spring.flyway.enabled=false`; os testes a habilitam sobre bancos descartáveis.
- **Nada é apagado:** nenhuma FK usa `ON DELETE CASCADE` (isso também evita o erro 1785 da proposta original). Versões saem do rascunho uma vez e ficam imutáveis; o gatilho `trg_int_version_guard` impõe isso no banco, além do código.
- **Repositórios com tenant explícito:** estendem `Repository`, nunca `JpaRepository`/`CrudRepository`, e todo método de consulta de dado de tenant recebe `tenantId` (um teste ArchUnit impede o contrário). Só `TenantRepository` e `ApiClientRepository` fazem busca global, porque resolvem o tenant.
- **Entidades guardam chaves, não associações:** colunas `*_id` como `Long`, sem `@ManyToOne` nem cascata, e sem coleções. `Instant` é gravado como `DATETIME2` em UTC (`HibernatePropertiesCustomizer`).
- **Auditoria fora da JPA para escrita:** `AuditExecutionEntity` é `@Immutable` e serve às consultas; a gravação em lote usa JDBC (E17). `int_audit_payload` só é acessada por JDBC. A chave clusterizada começa por `created_at` para a partição mensal da E17.
- **Papel de administrador da plataforma** vem do provedor OIDC; `int_user_role` só tem VIEWER, INTEGRATOR e PUBLISHER.
- **Testes de banco** usam um banco novo por classe: o servidor de `LIBERTY_TEST_DB_*` se definido, senão Testcontainers; falham com mensagem clara se não houver nenhum dos dois.

## Consequências

- `make verify` lê `.env.local` para achar o SQL Server local; no CI o Testcontainers sobe o servidor.
- O Hibernate valida as entidades contra o schema real (`ddl-auto=validate`) em cada build; coluna nova exige migração nova, nunca edição da V1.
- Entidades ficam fora da cobertura mínima do JaCoCo (são mapeamento); a lógica é testada pelos repositórios e pelos testes de schema.

## Alternativas descartadas

- Flyway rodando na subida de cada instância: corrida entre instâncias e exige DDL no usuário da aplicação.
- `JpaRepository` com filtro de tenant por convenção: um `findById` esquecido vira vazamento entre tenants.
- Associações JPA com cascata: risco de apagar histórico e consultas N+1 não previstas.
