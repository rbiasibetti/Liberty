# 0017. Scripts de integradores em processos trabalhadores

- Status: aceito
- Data: 2026-11-06
- Etapa: E04 (prova de conceito em `backend/poc/script-isolation`, perfil Maven `poc`)
- Complementa: [0007](0007-graaljs-pela-api-polyglot-como-unico-motor-de-script.md)

## Contexto

O ADR 0007 fixou GraalJS pela API Polyglot, mas deixou aberto como conter CPU e memória na edição Community. A PoC rodou 19 scripts hostis contra um `Context` por execução (`HostAccess.EXPLICIT`, sem IO, threads, processos, ambiente nem lookup de classes Java, `statementLimit` e cancelamento por prazo) em OpenJDK 25.

## Resultados medidos

| Ataque | Contido em processo? | Como |
| --- | --- | --- |
| `Java.type`, `Packages`, `java.*`, reflexão no objeto da API, método não exportado | Sim | `TypeError` ou acesso negado; só `@HostAccess.Export` é visível |
| `load('/etc/passwd')`, `require`, `import()`, `setTimeout`, `Atomics.wait` | Sim | Negado, indefinido ou não suportado |
| Laço infinito | Sim | `statementLimit` ou cancelamento por prazo (`context.close(true)`) em até 2 s |
| Regex catastrófica, recursão infinita, string gigante | Sim | Termina; `RangeError` do motor |
| Inundação de `console.log` | Sim | Saída limitada a 64 KB |
| Estado entre execuções (`globalThis`) | Sim | Um `Context` por execução; nada sobrevive |
| Bomba de memória | **Não** | `sandbox.MaxHeapMemory`, `sandbox.MaxCPUTime` e `sandbox.MaxStackFrames` exigem Truffle Enterprise e são recusadas na Community |

Com heap de 64 MB e três threads executando scripts normais ao lado de uma bomba de memória, 2 a 4 de cerca de 2.300 execuções normais falharam e a latência máxima subiu de dezenas para 841 ms; com 128 MB o prazo cancelou a bomba antes do esgotamento e nada falhou. Ou seja, a proteção de memória em processo depende de folga de heap e de sorte.

Com processos trabalhadores (JVM própria, `-Xmx` por trabalhador, `-XX:+ExitOnOutOfMemoryError`): bombas de memória, de objetos e laços não afetaram nenhuma das cerca de 10.000 execuções normais concorrentes (latência máxima 20 ms); um trabalhador sem heap morre e é substituído; um trabalhador que ignora o próprio prazo é morto pelo pai em cerca de 1,3 s.

Custo medido (laço de 2.000 iterações, JVM sem compilador Graal, execução aquecida): p50 de 1,8 ms em processo contra 4,1 ms pelo pool (p95 5,5 ms); 5 trabalhadores ficam prontos em cerca de 1,3 s.

## Decisão

Scripts rodam fora do processo da API, em um pool de JVMs trabalhadoras. O trabalhador mantém todos os controles em processo (defesa em profundidade). O pai aplica um prazo duro e mata (`SIGKILL`) e repõe o trabalhador que o estourar, e responde 504 (prazo) ou 503 (trabalhador indisponível).

Requisitos para a E14:

- O trabalhador não recebe credenciais, token nem segredos: só o script, a entrada e o prazo. O ambiente do processo é limpo (`ProcessBuilder.environment().clear()`).
- `db.lookup` e `db.named` viram chamadas ao pai pelo mesmo canal (stdin/stdout); só o pai tem conexão e contexto do tenant, e limites de linhas e chamadas valem no pai.
- Tamanho do pool e `-Xmx` por trabalhador vêm da configuração; o limite de memória do contêiner precisa cobrir a soma `trabalhadores × (Xmx + 100 MB)`. Admissão: no máximo N scripts simultâneos por tenant (bulkhead) e fila curta; fila cheia responde 429.
- Um trabalhador é reciclado a cada 1.000 execuções ou 10 minutos para limitar fragmentação e vazamentos do motor.
- A PoC é descartável; o código de `ScriptRunner`, `WorkerMain` e `ProcessScriptPool` é a base da E14, com o protocolo em Base64/TAB trocado por JSON-lines com versão.

## Consequências

- A decisão por GraalJS Community em JDK comum (sem Oracle GraalVM) se mantém, sem dependência de licença do Truffle Enterprise.
- A latência por execução sobe cerca de 2 a 3 ms; é aceitável diante do p95 de 300 ms do SLO.
- O pool é um componente de operação a mais: métricas de trabalhadores ocupados, mortos e tempo de espera por trabalhador entram na E17.
- Interpretação sem compilador Graal limita o desempenho de scripts pesados; o limite de instruções e o prazo protegem o serviço, e scripts que precisarem de mais CPU devem virar transformações declarativas.

## Alternativas descartadas

- **Só em processo:** não isola memória na Community (resultados acima).
- **Oracle GraalVM com Truffle Enterprise (`SandboxPolicy.UNTRUSTED`, `sandbox.MaxHeapMemory`):** exige licença GFTC e o JDK Oracle GraalVM; reavaliar se a operação aceitar ambos.
- **Um processo por execução:** a partida a frio leva cerca de 0,7 a 1,3 s.
