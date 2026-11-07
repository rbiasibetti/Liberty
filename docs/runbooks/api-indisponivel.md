# A API não responde ou responde 5xx em massa

**Reconhecer:** alerta `LibertyInstanciaForaDoAr` ou `LibertyErros5xxAltos`; consumidores relatando 5xx; o painel mostra a taxa de erro subindo.

**Olhar primeiro**
1. `GET /actuator/health/readiness`: se `db` está `DOWN`, o banco central (`liberty_core`) está inacessível. Veja o runbook [restaurar-core.md](restaurar-core.md) se o banco foi perdido.
2. Uma instância só ou todas? Uma só: tire-a do balanceador e reinicie. Todas: o problema é compartilhado (banco central, Vault ou rede).
3. Logs da instância com o `requestId` de uma resposta falha: o erro técnico fica só no log (a resposta é genérica de propósito).
4. Métricas: `hikaricp_connections_pending` do pool do core (esgotado?) e `liberty_script_workers`.

**Causas comuns e correção**
- Banco central fora: restabeleça o banco; a aplicação volta sozinha (pool reconecta). Execuções já em andamento falham; escritas não deixam meia gravação (cada uma é uma transação).
- Vault fora: leituras do tenant falham com 503 `tenant-unavailable` para tenants sem pool aberto; os com pool aberto seguem até o pool expirar (30 min ociosos). Restabeleça o Vault.
- Instância sem memória (OutOfMemory): reinicie e revise `poolSize × (heap + 100 MB)` dos scripts contra o limite do contêiner (ADR 0026).

**Confirmar:** `/actuator/health/readiness` `UP`, a taxa de 5xx volta ao normal e `LibertyErros5xxAltos` sai do estado de disparo.
