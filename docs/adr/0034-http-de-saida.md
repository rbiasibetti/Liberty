# 0034. HTTP de saída dos scripts (`http.fetch`)

- Status: aceito
- Data: 2026-11-07
- Etapa: E22

## Contexto

Os scripts só liam o banco (`db.named`, `db.lookup`). Casos reais precisam consultar uma API do cliente, por exemplo para validar um documento antes de gravar. Uma chamada HTTP feita a partir do servidor, com a URL escrita por um integrador, é o caso clássico de SSRF: alcançar metadados de nuvem (169.254.169.254), serviços internos, o próprio Vault ou o banco central.

## Decisão

**API.** `http.fetch(url, {method, headers, body})` síncrono, no trabalhador de script como `db`. A chamada vai ao processo pai (o trabalhador continua sem rede) e devolve `{status, ok, headers, body}` em texto. Objetos em `body` viram JSON. Erros de política são exceções com texto genérico, capturáveis pelo script.

**Defesa em camadas, na ordem em que são aplicadas:**
1. Só `https`, método em `GET/POST/PUT/PATCH/DELETE`.
2. A URL precisa ser um nome DNS: IP em qualquer notação (decimal, hexa, octal, IPv6), `localhost`, nome sem ponto, ponto final, rótulo vazio, caracteres não ASCII, barra invertida, espaços, `user:senha@` e portas inválidas são recusados antes de qualquer rede.
3. **Lista de permissão por tenant** (`int_tenant_http_host`, V4), gerida só pelo administrador da plataforma. A entrada é o nome exato, mais `:porta` quando não é a 443; subdomínios não herdam. Cache de 5 s.
4. Cabeçalhos: sem `Host`, `Content-Length`, `Transfer-Encoding`, `Cookie`, `Connection`, `X-Forwarded-*`, `Accept-Encoding` etc.; sem CR/LF; no máximo 16.
5. **Todo endereço resolvido tem de ser público**, verificado no resolvedor de DNS do cliente HTTP, isto é, no instante da conexão e sobre os mesmos endereços usados nela. Não há "valida e depois conecta", então reassociação de DNS não abre janela. Se qualquer endereço da resposta DNS não for público, a chamada falha. Recusados: loopback, `0.0.0.0/8`, privados, CGNAT, link-local (metadados), multicast, reservados, documentação, benchmarking, ULA, IPv6 mapeado/compatível/NAT64/6to4.
6. **Sem redirecionamentos** (o script recebe o 3xx e decide, passando de novo pela política), sem proxy, sem cookies, sem descompressão.
7. Limites: prazo total de 5 s (cancelamento ativo, não só tempo de leitura), resposta de até 1 MiB, corpo de até 256 KiB, 5 chamadas por execução.

**Sandbox.** A chamada passa pela mesma validação (URL, lista de permissão, cabeçalhos, corpo, contagem) e **não envia nada**: devolve `{status: 200, ok: true, simulated: true}`. Assim o integrador descobre no sandbox um host não liberado. Não há respostas configuráveis nesta etapa; um script que ramifica pelo conteúdo da resposta só é exercitado de verdade em HML/PRD.

**Prazo do script.** O relógio do script inclui a espera pela chamada. Scripts que contêm `http.fetch` ganham o prazo da chamada de saída somado ao do script (2 s + 5 s); os demais seguem com 2 s.

**Sem chave para afrouxar.** Nenhuma propriedade desliga a política de endereços ou o HTTPS; só limites de tamanho, tempo e quantidade são configuráveis (`liberty.outbound.*`). A política "tudo permitido" existe apenas no construtor de testes.

**Dependência.** Apache HttpClient 5 (`httpclient5`): permite pôr a verificação de endereços no resolvedor de DNS do próprio cliente, o que o cliente HTTP do JDK não permite.

## Consequências

- A suíte `OutboundHttpTest` (SSRF) cobre dezenas de formas de URL e de endereços proibidos (e os públicos que devem passar), DNS que aponta para dentro (inclusive resposta mista), redirecionamento para os metadados, tamanhos, prazo, cabeçalhos proibidos e o sandbox. `LifecycleContractTest` e `WorkerPoolTest` cobrem o fluxo pelo trabalhador e pelo sandbox da API.
- Falta uma tela para a lista de hosts: hoje só a API. A lista vive no core e vale para HML e PRD do tenant.
- O ponto cego é um host liberado que o próprio cliente controla e que responde com conteúdo hostil: o script o recebe como texto e é responsável por validá-lo.
- Auditoria registra o host (nunca caminho, consulta, cabeçalhos ou corpo) em log; ainda não há métrica própria de chamadas de saída.
