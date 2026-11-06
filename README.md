# Liberty

Plataforma multitenant de integração orientada a metadados: endpoints REST configurados e publicados sem deploy, mapeamento de JSON para o SQL Server de cada cliente, regras em JavaScript isoladas e auditoria. O plano completo está na Proposta de Implementação v2 (Claude Docs); as decisões de arquitetura estão em [docs/adr](docs/adr/README.md).

## Estrutura

| Pasta | Conteúdo |
| --- | --- |
| `backend/` | Maven multi-módulo: `liberty-domain` (Java puro), `liberty-core-migrations` (Flyway do core), `liberty-infrastructure` (JPA e adaptadores) e `liberty-app` (Spring Boot). |
| `frontend/` | React + TypeScript + Vite. |
| `docs/adr/` | Registros de decisão de arquitetura. |

## Pré-requisitos

- JDK 25 (o wrapper baixa o Maven 3.9)
- Node.js 20.19 ou superior (22 LTS recomendado) e npm

## Comandos

```bash
make setup     # ativa os hooks de commit (Conventional Commits)
make verify    # formatação, lint, testes, cobertura e build do backend e do frontend
make format    # aplica Spotless e Prettier
make mutation  # testes de mutação do backend (lento)
make migrate-core # aplica as migrações no liberty_core local
make local-up  # sobe o ambiente local completo (veja abaixo)
make local-down
```

Os testes de banco usam o SQL Server de `LIBERTY_TEST_DB_*` (lido de `.env.local` pelo `make verify`) ou, sem ele, um container via Testcontainers. Para rodar a aplicação contra o core local: `LIBERTY_CORE_URL` e `LIBERTY_CORE_APP_PASSWORD` (usuário `liberty_core_app`).

Provas de conceito descartáveis ficam em `backend/poc` e só entram no build com `-Ppoc` (`cd backend && ./mvnw -Ppoc verify`).

Requisitos não funcionais e SLOs: [docs/requisitos-nao-funcionais.md](docs/requisitos-nao-funcionais.md); testes de carga em `loadtest/` (k6, rodam toda noite).

Rodar a aplicação: `cd backend && ./mvnw -pl liberty-app spring-boot:run` e `cd frontend && npm run dev`.

## Ambiente local

`make local-up` sobe, com um comando e só em `127.0.0.1`: Redis, Vault (modo dev), Keycloak (realm `liberty`, usuário `integrador`), Prometheus, Grafana e Jaeger, e prepara o SQL Server com os bancos `liberty_core`, `tenant_cliente_a` e `tenant_cliente_b` (tabelas de exemplo, uma com trigger, e usuário da aplicação sem DELETE nem DDL). Se já existir um SQL Server em `LIBERTY_TEST_DB_HOST:LIBERTY_TEST_DB_PORT`, ele é reaproveitado; senão o container 2022 sobe pelo perfil `mssql`. O script é idempotente e gera as senhas que faltarem em `.env.local`; o endereço de cada serviço aparece no final. Requer docker compose ou podman. Para refazer só os bancos: `scripts/seed-sql.sh`.

## Segredos e ambiente local

Nenhum segredo vai para o Git. Credenciais locais ficam em `.env.local` (ignorado); veja `.env.example` para os nomes das variáveis.

## Contribuição

Veja [CONTRIBUTING.md](CONTRIBUTING.md) e [SECURITY.md](SECURITY.md).
