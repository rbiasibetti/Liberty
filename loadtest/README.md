# Testes de carga (k6)

Cenários derivados dos SLOs em [docs/requisitos-nao-funcionais.md](../docs/requisitos-nao-funcionais.md). Os limiares estão em `lib/config.js`; mudar um SLO exige mudar os dois.

| Cenário | Exige | O que prova |
| --- | --- | --- |
| `smoke.js` | E01 | A aplicação sobe e responde dentro do orçamento |
| `escrita.js` | E12 | INSERT master-detail de 100 itens, p95 abaixo de 300 ms a 50 req/s por tenant |
| `leitura.js` | E11 | SELECT paginado, p95 abaixo de 150 ms |
| `vizinho-barulhento.js` | E18 | Tenant a 10 vezes o plano não degrada os demais em mais de 20% |

```bash
k6 run loadtest/scenarios/smoke.js
BASE_URL=https://hml.exemplo TENANTS=cliente-a:TOKEN_A,cliente-b:TOKEN_B TOKEN_A=... TOKEN_B=... k6 run loadtest/scenarios/escrita.js
```

Tokens vêm de variáveis de ambiente, nunca do repositório. A execução noturna (`.github/workflows/nightly-load.yml`) roda os cenários listados em `nightly.txt`.
