# Portão 2: critérios do MVP

Estado verificado em 2026-11-07. "Demonstrado" quer dizer que um teste automatizado reproduzível prova o critério; "pendente" quer dizer que depende de algo que este repositório sozinho não entrega (infraestrutura, terceiros ou pessoas). Nada abaixo é dado como cumprido sem prova.

| # | Critério | Estado | Prova ou lacuna |
|---|---|---|---|
| 1 | Um integrador cria, testa no sandbox e envia para revisão uma escrita master-detail de 2 níveis, sem ajuda | **Demonstrado em máquina**, usabilidade sem ajuda **pendente** | E2E `frontend/e2e/fluxo-do-integrador.spec.ts` (Playwright no Chrome, API real, OIDC com PKCE): login, criação, JSON Schema a partir de exemplo, mapeamento de 2 níveis, sandbox com desfazimento, envio para revisão. "Sem ajuda" exige observar uma pessoa real fazendo a tarefa |
| 2 | O aprovador publica HML → PRD e faz rollback em menos de 1 minuto | **Demonstrado** | Mesmo E2E: aprovação (autor ≠ revisor), promoção para PRD, v2 em HML e rollback para a v1, com cronômetro de 60 s; tempos reais ficam em segundos. Regras de ciclo de vida: `LifecycleContractTest` |
| 3 | Testes de acesso entre tenants passam em todos os endpoints | **Demonstrado** | `CrossTenantAccessTest` percorre todos os endpoints da especificação OpenAPI (que o `AdminApiSpecTest` obriga a estar completa): usuário só do tenant B recebe 403 em toda rota do tenant A; anônimo recebe 401 e cliente de sistema 403 nas rotas de usuário; cliente de sistema de B não alcança as rotas de execução de A. Mais `EveryEndpointIsProtectedTest` e `SecurityAccessTest` |
| 4 | Script em laço infinito é interrompido sem afetar os demais | **Demonstrado** | `WorkerPoolTest.runawayScriptsAreCutOffAndTheWorkerIsReplaced` (`while (true) {}` termina em TIMEOUT, o trabalhador é trocado e o próximo script roda) e `memoryBombsKillOnlyTheirWorker`; ver ADR 0026 |
| 5 | 50 req/s × 10 tenants dentro do p95 | **Pendente** | Cenários k6 em `loadtest/` (`escrita`, `leitura`, `vizinho-barulhento`) existem e os limiares seguem os SLOs, mas **não foram executados**: não há k6 nem ambiente de implantação aqui. Medição em processo mostrou o vizinho barulhento subindo de 35 para 74 ms, acima do critério de 20%; só uma execução contra uma instância implantada decide |

## Outras entregas da E21

| Item do plano | Estado |
|---|---|
| E2E com Playwright | Feito (`scripts/e2e.sh`; um fluxo completo). Não roda na esteira de CI: precisa de SQL Server e do Chrome |
| Carga com k6 | Cenários prontos, **não executados** (critério 5) |
| DAST com OWASP ZAP | **Pendente**: precisa de instância implantada e do ZAP |
| Pentest externo | **Pendente**: contratação de terceiro. Modelo de ameaças e checklist ASVS nível 2 prontos para entregar à equipe: [seguranca/](seguranca/) |
| Runbooks | Feitos: [runbooks/](runbooks/README.md) |
| Backup e restauração do core testados | Feito: `BackupRestoreTest` (backup com checksum, verificação, restauração sob outro nome, conferência de dados, migrações e gatilhos) e [runbooks/restaurar-core.md](runbooks/restaurar-core.md). Backup de log e RPO de 15 min são política, não foram exercitados |
| Piloto em PRD | **Pendente**: depende de um cliente real |

## Veredito

O Portão 2 **não está cumprido**. Os critérios 2, 3 e 4 têm prova automatizada; o 1 tem prova técnica mas falta a observação com uma pessoa; o 5 não foi medido. Para fechar: (a) rodar o k6 contra uma implantação de homologação e registrar os p95 aqui; (b) sessão com um integrador real; (c) DAST e pentest; (d) piloto.

## Defeitos que só o E2E achou

- Após o login, a primeira chamada à API saía sem token e mandava o usuário de volta ao login (o token chegava ao cliente da API depois dos efeitos dos filhos). Corrigido com efeito de layout e teste de regressão.
- No modo de desenvolvimento a tela de retorno do login trocava o código duas vezes e o provedor recusava a segunda. Corrigido (troca única) e coberto por teste.

## Como repetir

```bash
make verify               # unidade, banco, contrato e acesso entre tenants
scripts/e2e.sh            # E2E no Chrome instalado; sobe a API de teste sozinho
```
