# Modelo de ameaças (STRIDE)

Superfícies da plataforma e, para cada ameaça, o controle que existe hoje (com o ADR ou teste que o prova) e o que falta. É um documento vivo: toda etapa que abre superfície nova o atualiza.

Legenda de estado: **ok** = controle implementado e testado; **parcial** = implementado com ressalva; **falta** = ainda sem controle.

## 1. API de execução (`/{tenant}/api/**`, sistemas de clientes)

| Ameaça | Controle | Estado |
| --- | --- | --- |
| **S**poofing: chamar como outro tenant | O tenant e o ambiente vêm só do JWT assinado (RS256, 15 min); a rota exige o mesmo slug (404 caso contrário); só o emissor local produz `ROLE_CLIENT`. Teste: `SecurityAccessTest`, `CrossTenantAccessTest`. | ok |
| **T**ampering: alterar SQL ou identificadores por parâmetro | Identificadores vêm do catálogo do tenant (nunca da requisição) e todo valor é parâmetro tipado; fuzzing no `ReadContractTest`, texto hostil gravado literalmente no `WriteEngineTest`. ADR 0022. | ok |
| **R**epudiation: negar uma chamada | Auditoria assíncrona por execução com `requestId` e cliente (ADR 0029). Perde eventos se a fila encher (medido, alertado). | parcial |
| **I**nformation disclosure: erro vaza SQL ou dados | Respostas `problem+json` genéricas, `instance` sem o caminho recebido, mensagens de banco traduzidas por número de erro; nada de valor recebido em mensagem. ADR 0024 e 0025. | ok |
| **D**enial of service: inundação, corpo gigante, JSON malicioso | Limite de taxa e simultaneidade por tenant (ADR 0030), corpo até 5 MB, 10.000 itens por lista, profundidade 32, chaves duplicadas recusadas, prazo de consulta e de escrita. Prova do isolamento entre tenants sob carga pendente (k6 contra instância implantada). | parcial |
| **E**levation: escrever sem permissão | Escopo `integration:execute:<chave>` por integração; o usuário do banco do tenant não tem `DELETE` nem DDL. | ok |

## 2. API de administração (`/api/v1/**`, usuários e administradores)

| Ameaça | Controle | Estado |
| --- | --- | --- |
| **S** | Token OIDC validado (emissor, audiência/`azp` da interface); cliente de sistema nunca acessa rota de usuário (e vice-versa). | ok |
| **T** | Versões imutáveis (gatilho no banco), quatro olhos na publicação (também por restrição no banco), conferências de publicação. ADR 0027. | ok |
| **R** | Ações do ciclo de vida e da administração ficam no log com o usuário; a trilha em tabela própria **ainda não existe** para essas ações. | parcial |
| **I** | Papéis lidos a cada requisição; tenant inexistente e não-membro dão a mesma resposta; senhas nunca voltam em resposta; segredo de cliente só aparece na criação. | ok |
| **D** | Mesmos limites; consulta de auditoria com janela máxima de 31 dias e página de até 100. | parcial |
| **E** | Administração do tenant só com o papel de administrador da plataforma (vem do provedor, nunca do banco). `EveryEndpointIsProtectedTest` exige `@PreAuthorize` em todo endpoint. | ok |

## 3. Scripts de integradores (código de terceiros dentro da plataforma)

| Ameaça | Controle | Estado |
| --- | --- | --- |
| Acesso a Java, arquivos, rede, ambiente, threads | `Context` novo por execução, `HostAccess.EXPLICIT`, sem lookup de classes; teste hostil no `WorkerPoolTest`. | ok |
| Laço infinito, regex catastrófica, recursão | Limite de instruções e cancelamento por prazo; o pai mata o trabalhador que ignora o prazo. | ok |
| Bomba de memória | JVM própria por trabalhador com `-Xmx` e saída em OutOfMemory; só o trabalhador morre. ADR 0017. | ok |
| Roubo de credenciais | O trabalhador não recebe credencial, token nem segredo; ambiente do processo vazio; o acesso a dados passa pelo pai (somente `SELECT`, 20 chamadas, 1.000 linhas). | ok |
| Efeito externo (rede, e-mail) | O motor não oferece nenhum; efeitos futuros devem recusar-se em sandbox (ADR 0028). | ok (por ausência) |

## 4. Banco do cliente e cofre

| Ameaça | Controle | Estado |
| --- | --- | --- |
| Abuso da URL de conexão (SSRF, arquivos, código) | Política de URL: só SQL Server, `encrypt=true`, propriedades perigosas proibidas; também no teste de conexão. | ok |
| SSRF pelo `http.fetch` dos scripts | Só HTTPS, só hosts liberados pelo administrador por tenant (nome DNS, nunca IP), todo endereço resolvido tem de ser público (checado na hora da conexão, sem janela de reassociação de DNS), sem redirecionamento, proxy nem cookies, prazo e tamanhos limitados, sandbox só simula. ADR 0034; suíte `OutboundHttpTest`. | ok |
| Tenant com consumidor lento ou mensagem venenosa trava a fila dos outros | Teto de mensagens em processamento por tenant (menor que o total da instância), reivindicação por tenant do mais antigo, mensagens independentes, recuo e fila de mortas; fila de cada tenant limitada em 10 mil. ADR 0035; `QueueTest`. | ok |
| Vazamento da senha do banco | Só no Vault; o core guarda a referência; nunca em resposta, log ou erro. | ok |
| Mistura de conexões entre tenants | Roteamento por contexto de tenant, um pool por tenant; teste com 50 tenants concorrentes. ADR 0020. | ok |
| Gatilho do cliente com efeito externo (`xp_cmdshell`, e-mail) | Fora do nosso controle: o usuário do banco do tenant não deve ter esses privilégios. | falta (exigir no onboarding) |

## 5. Interface web

| Ameaça | Controle | Estado |
| --- | --- | --- |
| XSS e roubo de sessão | React escapa a saída; CSP restritiva no nginx (`script-src 'self'`); sessão no `sessionStorage` com tokens de 15 min. A liberação do CSP para o provedor OIDC ainda precisa ser feita no empacotamento. | parcial |
| Redirecionamento aberto após login | O destino guardado só aceita caminhos internos. | ok |
| Clickjacking | `X-Frame-Options: DENY` e `frame-ancestors 'none'`. | ok |

## Lacunas conhecidas, em ordem de risco

1. Teste de penetração externo e varredura DAST ainda não feitos.
2. Isolamento sob carga entre tenants só medido em processo único (p95 de 35 para 74 ms), abaixo do critério de 20% ainda não demonstrado.
3. Trilha de auditoria das ações administrativas só em log.
4. Privilégios do usuário do banco do tenant dependem do onboarding do cliente.
