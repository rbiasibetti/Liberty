# Requisitos não funcionais e SLOs

Valores adotados em 2026-11-06 ([ADR 0018](adr/0018-slos-e-capacidade.md)). Os cenários de carga em `loadtest/` derivam destes números; mudar um valor exige atualizar este arquivo, o ADR e os limiares do k6 no mesmo PR.

## Capacidade

| Item | Valor | Observação |
| --- | --- | --- |
| Tenants em 12 meses | até 100 | Até 20 ativos ao mesmo tempo |
| Pico por tenant | 50 req/s | Plano padrão; planos maiores configuram o rate limit |
| Pico agregado da plataforma | 500 req/s | 10 tenants no pico simultâneo, base do teste de carga |
| Itens por requisição de escrita | até 100 típicos, 10.000 no máximo | Limite de array por plano |
| Corpo da requisição | 5 MB no máximo | Acima disso, 413 |
| Execuções de script por segundo | 100 por instância | Limitado pelo pool de trabalhadores (ADR 0017) |
| Tamanho do snapshot de integração | 1 MB no máximo | |

## SLIs e SLOs

Medidos no servidor, por tenant e por integração, em janela de 30 dias, excluindo o tempo gasto no banco do cliente (`liberty.integration.db.duration`) e requisições recusadas por limite do plano (429).

| SLI | SLO | Fonte |
| --- | --- | --- |
| Disponibilidade da API de execução: respostas que não sejam 5xx do total | 99,5% por mês (orçamento de 3 h 39 min) | `liberty.integration.requests` |
| Latência de INSERT de até 100 itens, descontado o banco do cliente | p95 abaixo de 300 ms, p99 abaixo de 800 ms | `liberty.integration.duration` |
| Latência de SELECT paginado (100 linhas), descontado o banco do cliente | p95 abaixo de 150 ms | idem |
| Latência extra do script (pool de trabalhadores) | p95 abaixo de 50 ms em scripts de até 2.000 instruções | `liberty.script.duration` |
| Publicação de versão (da aprovação à ativação) | p95 abaixo de 10 s | auditoria |
| Rollback para a versão anterior | abaixo de 60 s | auditoria |
| Isolamento entre tenants: tenant a 10 vezes seu plano não altera o p95 dos demais | variação menor que 20% | teste `noisy-neighbor` (k6, `loadtest/scenarios/vizinho-barulhento.js`); ADR 0030 |
| Perda de eventos de auditoria | 0 em operação normal; qualquer descarte gera alerta | `liberty.audit.dropped` |

Política de orçamento de erro: com 50% do orçamento mensal consumido, mudanças que não sejam correções ficam congeladas até o fim da janela.

## Retenção, backup e recuperação

| Item | Valor |
| --- | --- |
| Auditoria online | 90 dias |
| Auditoria arquivada | 5 anos, no object storage |
| Versões de integração | Nunca apagadas (arquivadas) |
| Backup do `liberty_core` | Completo diário, log a cada 15 min, retenção de 35 dias |
| RPO do core | 15 minutos |
| RTO do core | 4 horas, com restauração testada a cada trimestre |
| Bancos dos tenants | Responsabilidade do cliente |

## Segurança e privacidade (não funcionais)

- TLS 1.2 ou superior em toda comunicação; HSTS no frontend.
- Tokens de acesso de 15 minutos; segredos só em cofre; rotação de chaves JWKS a cada 90 dias.
- Nenhum dado pessoal em log; payloads de auditoria mascarados (LGPD).
- Pentest externo antes de abrir para mais tenants (portão 2) e a cada mudança estrutural de segurança.
- Varredura de dependências diária; correção de vulnerabilidade alta ou crítica em até 7 dias.

## Compatibilidade

- Navegadores: duas últimas versões estáveis de Chrome, Edge, Firefox e Safari.
- Banco do cliente: SQL Server 2019 ou superior.
- Acessibilidade da interface: WCAG 2.2 nível AA nas telas do MVP.
