# Cliente recebendo 429

**Entender:** cada tenant tem limite de requisições por segundo (padrão 50, rajada de 100) e de execuções simultâneas (20), definidos pelo plano (ADR 0030). `429 rate-limited` traz `Retry-After`; `429 tenant-busy` quer dizer simultaneidade; `429 too-many-requests` vem dos scripts.

**Olhar primeiro:** `liberty_traffic_rejected_total{tenant,reason}`: é rajada legítima ou laço do cliente? O cliente respeita `Retry-After`?

**Correção:** orientar o consumidor a espaçar chamadas e respeitar `Retry-After`; se a necessidade é legítima, definir um plano maior em `liberty.limits.plans.<PLANO>` (taxa, rajada, simultaneidade) e vincular o tenant (`int_tenant.plan_code`). Sem Redis o limite é por instância (o conjunto pode somar até N vezes o limite); em produção configure `LIBERTY_REDIS_URI`.
