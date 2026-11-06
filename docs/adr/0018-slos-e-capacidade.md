# 0018. SLOs e capacidade

- Status: aceito
- Data: 2026-11-06
- Etapa: E05

## Contexto

A proposta v2 listava volumes, SLA e retenção como propostas a confirmar. Sem números fixos não há como dimensionar pools, definir limites de plano nem escrever testes de carga com critério de aprovação.

## Decisão

Os valores da proposta passam a ser requisitos: 100 tenants em 12 meses, 50 req/s por tenant, 500 req/s agregados, disponibilidade de 99,5% ao mês, p95 abaixo de 300 ms para INSERT de até 100 itens, retenção de auditoria de 90 dias online e 5 anos arquivada. A lista completa, com SLIs e o orçamento de erro, está em [requisitos-nao-funcionais.md](../requisitos-nao-funcionais.md).

Os cenários k6 de `loadtest/` usam esses números como limiares e rodam toda noite; um cenário só entra na execução noturna quando o endpoint que ele exercita existe.

## Consequências

- Rate limit, pools e limites de payload (E12, E18) são dimensionados a partir destes números.
- Mudança de qualquer requisito exige novo ADR que substitua este e ajuste os limiares do k6.
- Os requisitos assumem o plano padrão; planos maiores ampliam limites sem mudar os SLOs.
- Se o piloto (portão 2) mostrar volumes muito diferentes, a revisão acontece nesse portão.

## Alternativas descartadas

- Manter os valores como perguntas em aberto: bloqueia o dimensionamento e os testes de carga.
- SLOs por tenant contratados individualmente: fora do escopo do MVP.
