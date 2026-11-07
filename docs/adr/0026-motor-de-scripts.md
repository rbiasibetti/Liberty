# 0026. Motor de scripts em processos trabalhadores

- Status: aceito
- Data: 2026-11-07
- Etapa: E14
- Implementa: [0017](0017-scripts-em-processos-trabalhadores.md)

## Contexto

O ADR 0017 decidiu isolar scripts de integradores (GraalJS Community) em JVMs trabalhadoras. A E14 transforma a prova de conceito em componente de produção e o liga aos motores de leitura e escrita.

## Decisão

**Módulo `liberty-scripts`** (sem Spring): `ScriptExecutor` (contrato), `WorkerPool` (lado do pai) e `WorkerMain`/`Sandbox` (lado do trabalhador). O motor (`liberty-engine`) só conhece `ScriptHooks`.

**Contrato do script.** Cada gancho do snapshot é um script completo que define a função `beforeExecute(input)` ou `afterExecute(input)`:
- `beforeExecute` (só em escrita) recebe `{params, body}` e devolve o novo corpo (objeto); `null`/`undefined` mantém o corpo. Roda depois da validação do JSON Schema e antes do mapeamento; o hash de idempotência usa o corpo original do cliente.
- `afterExecute` recebe `{params, body, result}` (`body` ausente em leitura) e devolve a nova resposta; `null` mantém. Em escrita roda **dentro da transação**: se o script chamar `fail`, nada é confirmado. Em leitura roda sobre a resposta já montada.
- API disponível: `db.named(nome, params)` (consulta nomeada do snapshot, devolve array de linhas), `db.lookup(tabela, colunaChave, chave, colunaValor)` (escalar ou `null`), `fail(status, mensagem)` (status 4xx; outro valor vira 422) e `console.log` (limitado a 64 KB, só em log de depuração). Nada mais: sem Java, arquivos, rede, threads, processos nem ambiente.
- `beforeExecute` em integração de leitura é ignorado pelo motor; a publicação (E15) deve recusá-lo.

**Trabalhadores.** JVM por trabalhador com `-Xmx` configurável (128 MB), `-XX:+ExitOnOutOfMemoryError`, ambiente de processo vazio, diretório de trabalho temporário e sem credencial ou segredo. Um `Context` por execução, `HostAccess.EXPLICIT`, sem lookup de classes, `statementLimit` e cancelamento por prazo; o resultado é limitado a 1 MB. Os trabalhadores sobem sob demanda até `poolSize` (o primeiro script paga ~1,3 s de partida), são reciclados a cada 1.000 execuções ou 10 minutos e repostos quando morrem. O pai mata (SIGKILL) quem passar de `prazo + folga`.

**Protocolo.** JSON-lines versionado (`v: 1`) em stdin/stdout: `run` (pai → trabalhador), `call`/`callResult` (consulta ao pai durante a execução) e `result`. Só o protocolo vai a stdout (o `System.out` do processo é redirecionado para stderr, descartado).

**Acesso a dados.** O trabalhador não tem conexão: `db.*` viram chamadas ao pai, que as executa no banco do tenant (na transação da escrita, quando houver) com `SELECT` de uma instrução só (sem `;`, começando por `SELECT` ou `WITH`), parâmetros nomeados e tipados, prazo de 10 s, no máximo 1.000 linhas e 20 chamadas por execução. `lookup` usa identificadores do catálogo. Erros de banco chegam ao script como mensagens genéricas.

**Admissão.** No máximo `tenantConcurrency` (4) execuções simultâneas por tenant e espera curta por trabalhador (500 ms); esgotada, 429 com `Retry-After`.

**Erros HTTP.** Prazo ou limite de instruções, 504 `script-timeout`; `fail`, o status pedido (`script-rejected`); erro do script, 500 `script-error` genérico (detalhe só no log); trabalhador morto ou indisponível, 503 `script-unavailable` com `Retry-After`.

## Consequências

- O `-cp` do trabalhador é o classpath da aplicação (`liberty.scripts.worker-classpath` o substitui). Em um jar executável do Spring Boot (jars aninhados) isso não funciona: a imagem de produção deve rodar com classpath explodido ou apontar o `worker-classpath` para os jars do módulo. Ajustar o empacotamento fica para a E15/E16.
- A memória do contêiner precisa cobrir `poolSize × (heap + 100 MB)`.
- Métricas do pool (ocupados, mortos, espera) entram na E17.
