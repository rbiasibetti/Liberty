# Contribuição

## Fluxo

- Trunk-based: branches curtas a partir de `main`, nomeadas `tipo/descricao-curta`, integradas por pull request pequeno (idealmente menos de 400 linhas).
- Todo PR precisa de revisão e de CI verde. A proteção da branch `main` (revisão obrigatória, verificações obrigatórias, sem push direto, histórico linear) deve ser configurada no provedor do repositório.
- Commits seguem Conventional Commits (`feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, `perf`, `revert`), validados pelo hook em `.githooks/commit-msg`. Ative com `make setup`.

## Definição de pronto

Cada etapa do plano só termina quando cumpre:

- Testes unitários e de integração escritos; cobertura mínima de 80% das linhas.
- Formatação e Checkstyle/ESLint sem avisos; `make verify` verde.
- Contrato OpenAPI atualizado antes do código (contract-first).
- Modelagem de ameaças (STRIDE) registrada quando abre superfície nova; nenhum segredo, dado pessoal ou payload em log.
- Métricas, spans e runbook para todo componente novo.
- ADR para qualquer decisão estrutural.

## Regras de código

- O domínio (`br.com.liberty.domain`) não depende de Spring, Jakarta nem Jackson; o ArchUnit garante.
- SQL dinâmico só pelo construtor de SQL do motor: identificadores validados, valores sempre parametrizados.
- Sem `System.out`, `printStackTrace` nem `console.log`; use o logger.
- Dependências novas: versão fixa, licença compatível e sem CVE de severidade alta.

## CI (GitHub Actions)

| Workflow | Quando | O que faz |
| --- | --- | --- |
| `ci.yml` | PR e push em `main` | `backend` (`mvnw verify`), `frontend` (`npm run verify` + `npm audit`) e `secrets` (gitleaks) |
| `codeql.yml` | PR, push e semanal | Análise estática de Java e TypeScript (`security-extended`) |

Não há publicação de imagens nem deploy automático: os manifestos em `deploy/` ficam prontos para quando houver um ambiente de destino, e o CI de imagens, o Dependency-Check, o teste de carga noturno e o Dependabot foram removidos por ora. Atualize dependências manualmente (`mvn versions:display-dependency-updates`, `npm outdated`).
