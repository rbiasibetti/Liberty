# 0028. Sandbox de execução

- Status: aceito
- Data: 2026-11-07
- Etapa: E16

## Contexto

O integrador precisa testar uma versão (inclusive rascunho) com dados reais do ambiente de homologação sem deixar rastro nesse banco, e entender o que a integração fez: SQL, resultado e console dos scripts.

## Decisão

**Endpoint.** `POST /api/v1/tenants/{slug}/integrations/{key}/versions/{no}/sandbox` (papel INTEGRATOR), com `{params, body}`. Executa qualquer versão (rascunho, em revisão, publicada ou rejeitada) pelo mesmo motor da produção: validação do corpo, JSON Schema, scripts, mapeamento e SQL.

**Só HML, sempre desfeito.** O escopo é fixo em HML do tenant; PRD nunca. A escrita roda na transação do banco do cliente e termina com `setRollbackOnly` mesmo quando tudo dá certo; falhas também desfazem. Isso cobre gatilhos, colunas de identidade (a sequência pode avançar, o que o SQL Server não desfaz) e o `afterExecute` dos scripts. O `afterExecute` roda dentro dessa transação, como em produção. Leituras já são somente leitura.

**Sem efeitos externos.** O motor não tem hoje nenhum efeito além do banco do tenant: scripts só leem (`db.named`, `db.lookup`) e não há rede, arquivo ou webhook. Qualquer efeito externo que venha a existir deve recusar-se a agir em sandbox (esta é a regra de projeto que este ADR fixa). Gatilhos do próprio cliente que ajam fora do banco (como `xp_cmdshell` ou envio de e-mail do SQL Server) fogem do nosso controle; o usuário do banco do tenant não deve ter esses privilégios (ADR 0020).

**Sem idempotência.** O sandbox não exige nem reserva `Idempotency-Key`.

**Rastro.** Cada comando SQL é registrado com o texto (marcadores `:pN`, nunca os valores, que podem ser dados pessoais), linhas afetadas ou devolvidas e duração, até 200 comandos (depois, `sqlTruncated`). O console dos scripts (`console.log`) volta em `logs` (até 64 KB).

**Resultado.** Sempre HTTP 200 quando a versão existe: `{succeeded, status, body, problem, sql, sqlTruncated, logs, millis, rolledBack}`. Falhas da integração (validação, conflito, recusa do script, prazo) vêm em `problem` com o status e o tipo que a produção devolveria, junto do rastro. Versão inexistente é 404; sem o papel, 403.

**Deadlocks.** A transação de escrita desfeita por deadlock (1205) é repetida até 3 vezes com pausa curta (nada foi confirmado); descoberto ao rodar execuções concorrentes de UPSERT na mesma chave.

## Consequências

- 1.000 execuções de teste (900 de sucesso e 100 de falha, em 5 threads, árvore de 3 níveis com gatilho) não deixam nenhuma linha no banco de HML; o teste é automático.
- O sandbox consome pool de conexões do tenant como qualquer execução; os limites de tráfego (E18) devem tratá-lo como tráfego do tenant.
