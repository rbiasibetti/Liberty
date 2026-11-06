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

## CI/CD (GitHub Actions)

| Workflow | Quando | O que faz |
| --- | --- | --- |
| `ci.yml` | PR e push em `main` | `backend` (`mvnw verify`), `frontend` (`npm run verify` + `npm audit`), `secrets` (gitleaks), `dependency-review` (PR), `images` (build das imagens, varredura Grype, e em `main` push em GHCR com SBOM, proveniência e assinatura cosign sem chave), `deploy-dev` |
| `codeql.yml` | PR, push e semanal | Análise estática de Java e TypeScript (`security-extended`) |
| `security.yml` | Diário, PRs que mudam dependências e manual | OWASP Dependency-Check, falha com CVSS 7 ou mais |

Configuração única no repositório:

- **Branch protection em `main`:** exigir PR, 1 aprovação, histórico linear e os checks `backend`, `frontend`, `secrets`, `dependency-review`, `images`, `analyze (java-kotlin)` e `analyze (javascript-typescript)`.
- **Secret `NVD_API_KEY`:** necessário para `security.yml` (chave gratuita do NVD).
- **Deploy em dev:** crie o ambiente `dev`, o secret `KUBE_CONFIG` nele e a variável `DEPLOY_DEV_ENABLED=true`. Os manifestos estão em `deploy/k8s`; o job só implanta imagens por digest com assinatura verificada.
- **Dependências:** o Dependabot abre PRs semanais (Maven, npm, Actions e Docker). Actions e imagens base são fixadas por SHA/digest.
