# Liberty

Plataforma multitenant de integração orientada a metadados: endpoints REST configurados e publicados sem deploy, mapeamento de JSON para o SQL Server de cada cliente, regras em JavaScript isoladas e auditoria. O plano completo está na Proposta de Implementação v2 (Claude Docs); as decisões de arquitetura estão em [docs/adr](docs/adr/README.md).

## Estrutura

| Pasta | Conteúdo |
| --- | --- |
| `backend/` | Maven multi-módulo: `liberty-domain` (Java puro) e `liberty-app` (Spring Boot). Novos módulos entram nas etapas E06 em diante. |
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
```

Rodar a aplicação: `cd backend && ./mvnw -pl liberty-app spring-boot:run` e `cd frontend && npm run dev`.

## Segredos e ambiente local

Nenhum segredo vai para o Git. Credenciais locais ficam em `.env.local` (ignorado); veja `.env.example` para os nomes das variáveis.

## Contribuição

Veja [CONTRIBUTING.md](CONTRIBUTING.md) e [SECURITY.md](SECURITY.md).
