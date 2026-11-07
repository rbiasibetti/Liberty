# Scripts lentos, `script-timeout` ou `script-unavailable`

**Reconhecer:** alerta `LibertyScriptsLentos`; respostas 504 `script-timeout` (prazo de 2 s ou limite de instruções), 503 `script-unavailable` (trabalhador morto) ou 429 `too-many-requests` (sem trabalhador livre).

**Entender (ADR 0017 e 0026):** scripts rodam em processos trabalhadores isolados, com memória e prazo próprios. Um script hostil só derruba o seu trabalhador, que é reposto; as outras requisições seguem.

**Olhar primeiro**
1. `liberty_script_duration_seconds` por integração: qual integração? A auditoria mostra `script_duration_ms` por execução.
2. `liberty_script_workers`: sempre no máximo? Muitos 503 repetidos para uma só integração indicam um script que esgota a memória (`-Xmx128m` por padrão).
3. Chamadas de `db.named` e `db.lookup` lentas: o SQL do cliente é lento (os scripts têm prazo de 10 s por consulta e no máximo 1.000 linhas e 20 chamadas).

**Correção**
- Script com laço infinito ou pesado: corrija o script (nova versão) e publique; em emergência, faça rollback ou despublique a integração.
- Falta de capacidade: aumente `liberty.scripts.pool-size` (cada trabalhador custa heap + cerca de 100 MB; confira o limite do contêiner) ou `tenant-concurrency` se um tenant legítimo precisa de mais paralelismo.

**Confirmar:** p95 de `liberty_script_duration_seconds` abaixo de 50 ms e fim dos 503/504.
