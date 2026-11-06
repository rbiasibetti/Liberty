# 0007. GraalJS pela API Polyglot como único motor de script

- Status: aceito
- Data: 2026-11-06
- Referência na proposta v2: D7

## Contexto

Integradores escrevem regras em script que roda dentro do servidor.

## Decisão

Scripts são JavaScript executados em `Context` do GraalJS com `HostAccess.EXPLICIT`, sem IO, threads, classes Java ou ambiente, com prazo e `statementLimit`.

## Consequências

Limite de memória por script só existe na Oracle GraalVM; o isolamento de CPU e memória foi decidido na etapa E04: processos trabalhadores ([0017](0017-scripts-em-processos-trabalhadores.md)).

## Alternativas descartadas

Groovy e JSR-223 (sem sandbox real nem controle de acesso).
