# 0030. Proteção de tráfego

- Status: aceito
- Data: 2026-11-07
- Etapa: E18

## Contexto

Um tenant que dispara muito além do seu plano não pode consumir conexões, threads ou trabalhadores de script que são de todos. A proteção precisa ser barata (rejeitar antes de trabalhar), por tenant e configurável por plano.

## Decisão

**Admissão por tenant** (`TrafficGuard`), no início de cada execução em `/{tenant}/api/**`, antes de resolver a rota ou tocar o banco do cliente: (1) **taxa** — balde de fichas Bucket4j por tenant (taxa sustentada e rajada); (2) **simultaneidade** — bulkhead por tenant (`Semaphore`) com vaga devolvida ao fim da execução. Excedeu: `429` com `application/problem+json`; taxa traz `Retry-After`, `RateLimit-Limit` e `RateLimit-Remaining`; simultaneidade traz `Retry-After: 1`.

**Planos.** O plano vem de `int_tenant.plan_code` (cache de 1 min). `liberty.limits.rate-per-second`, `burst` e `max-concurrent` são o padrão (50, 100, 20: docs/requisitos-nao-funcionais.md); `liberty.limits.plans.<PLANO>.*` sobrescreve por plano (campo omitido herda o padrão). O balde é identificado por tenant, taxa e rajada, então trocar de plano troca o balde.

**Redis opcional.** Com `liberty.limits.redis-uri` (Lettuce) o balde é compartilhado por todas as instâncias (o limite do plano vale para o conjunto); sem ele, ou com o Redis fora do ar, vale o balde local de cada instância (limite por instância) e a falha é contada em `liberty.ratelimit.fallback`. A conexão é feita sob demanda e retentada a cada 10 s: a API sobe e responde com o Redis parado. Falhar aberto para o limite local foi escolhido no lugar de recusar tudo, porque derrubar a API junto com o Redis seria pior que limitar com folga.

**Métricas.** `liberty.traffic.rejected{tenant,reason}` (`rate` ou `concurrency`).

**O que já existia e continua valendo.** Corpo de escrita de até 5 MB, 10.000 itens por lista e 50.000 linhas por requisição (ADR 0025); prazo de 30 s nas leituras e 60 s nas escritas; prazo, memória e bulkhead de 4 execuções por tenant nos scripts (ADRs 0017 e 0026); pools de conexão limitados por tenant (ADR 0020).

## Consequências

- Rejeitar custa a validação do token e a cadeia de segurança, mas nenhuma consulta ao banco; ainda assim uma enxurrada consome CPU da instância. Em teste em processo único (cliente e servidor na mesma máquina), 12 threads a mais de 10 vezes o limite, o p95 do vizinho foi de 35 ms para 74 ms (sem recusas nem erros para o vizinho; 97% das chamadas do barulhento recusadas). Essa medida divide a CPU com o gerador de carga e **não demonstra** a variação abaixo de 20% pedida: o critério vale para o cenário k6 `loadtest/scenarios/vizinho-barulhento.js` contra uma instância implantada, que ainda não foi executado.
- Para proteger também a CPU contra volumes muito maiores, o próximo passo é rejeitar na borda (ingress ou WAF) antes de chegar à aplicação.
- O limite sem Redis é por instância: com N instâncias o tenant pode somar até N vezes o limite. Em produção, configure o Redis.
- O caminho Redis foi escrito contra a API do Bucket4j/Lettuce e testado só no fallback (não há Redis nesta máquina); existe um teste condicionado a `LIBERTY_TEST_REDIS_URI` que confere que duas instâncias dividem um só balde.
