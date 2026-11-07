# Runbooks de operação

Procedimentos curtos para o que mais provavelmente dá errado. Cada um diz como reconhecer, o que olhar primeiro, como resolver e como confirmar. Os alertas citados estão em `deploy/local/prometheus/alerts.yml` e o painel é o "Liberty - operação" do Grafana.

| Situação | Runbook |
| --- | --- |
| A API não responde ou responde 5xx em massa | [api-indisponivel.md](api-indisponivel.md) |
| Um tenant recebe 503 `tenant-unavailable` | [tenant-sem-conexao.md](tenant-sem-conexao.md) |
| Alerta de auditoria descartando ou perdendo eventos | [auditoria-descartando.md](auditoria-descartando.md) |
| Uma publicação deu problema em produção | [publicacao-e-rollback.md](publicacao-e-rollback.md) |
| Scripts lentos, 504 `script-timeout` ou 503 `script-unavailable` | [scripts-lentos.md](scripts-lentos.md) |
| Troca ou vazamento de chaves de assinatura | [chaves-jwt.md](chaves-jwt.md) |
| Backup, restauração e perda do banco central | [restaurar-core.md](restaurar-core.md) |
| Cliente recebendo 429 | [limites-429.md](limites-429.md) |

Regras gerais: toda resposta de erro traz `requestId` (cabeçalho `X-Request-Id`); com ele se acha a execução na auditoria (`GET /api/v1/tenants/{slug}/audit/executions?requestId=...`) e nos logs. Segredos nunca vão para chamado, chat ou log; se um vazou, trate como comprometido e troque.
