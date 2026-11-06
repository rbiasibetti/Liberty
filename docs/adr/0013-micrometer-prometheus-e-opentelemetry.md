# 0013. Micrometer, Prometheus e OpenTelemetry

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D13

## Contexto

Métricas e rastreamento próprios reinventam padrões de mercado.

## Decisão

Métricas com Micrometer e Prometheus, tracing com OpenTelemetry (agente Java), logs JSON no stdout. `request_id` é o trace id. O painel de saúde da fase 2 lê o Prometheus.

## Consequências

Nenhum contador próprio no Redis. Logs nunca carregam payload.

## Alternativas descartadas

Contadores no Redis enviados por WebSocket.
