# 0023. Modelo do snapshot de integração

- Status: aceito
- Data: 2026-11-06
- Etapa: E10

## Contexto

O ADR 0010 define que cada versão é um documento JSON imutável da integração inteira. Esse documento é escrito por pessoas, guardado por meses e executado a cada requisição, então precisa de formato estável, validação com erro por campo e uma identidade confiável (hash).

## Decisão

- **Formato.** JSON com `schemaVersion` inteiro e um JSON Schema 2020-12 publicado (`snapshot/snapshot-v1.schema.json`), estrito: campo desconhecido é erro. Seções: `integration`, `request` (parâmetros e schema do corpo), `operation` (`select` ou `write`), `scripts`, `namedQueries`, `response`, `idempotency` e `tests`. O mapeamento de escrita é uma árvore de nós (ADR 0008) com campos vindos de ponteiro no corpo, de parâmetro ou de constante, e transformações de uma lista fechada.
- **Modelo tipado** (`br.com.liberty.snapshot.model`, records) é o que o motor consome; o documento só vira modelo depois de validado.
- **Validação em duas camadas, um erro por campo.** Primeiro a estrutura (JSON Schema), depois a semântica, só em documentos estruturalmente sãos: coerência de rota, parâmetros, método e operação; árvore (profundidade 5, 30 nós, chaves estrangeiras, colunas repetidas); chaves de UPSERT e UPDATE; referências cruzadas; argumentos das transformações. Cada erro traz JSON Pointer, código estável e mensagem que nunca repete o valor recebido. A existência de tabelas e colunas é conferida na publicação (E15), contra o catálogo do ambiente de destino.
- **Restrições do MVP, impostas na validação:** `children` só em INSERT; UPSERT e UPDATE são de um único nó, por chave completa; operações de escrita precisam declarar o schema do corpo.
- **Entrada hostil.** Máximo de 1 MiB; JSON com chaves duplicadas, aninhamento acima de 64 níveis ou número longo é recusado. O schema do corpo (executado a cada requisição) precisa ser JSON Schema 2020-12 válido, ter até 32 níveis e 5.000 nós, referenciar só `#` (nunca arquivo ou URL, que seria leitura arbitrária ou SSRF), não ter `$id` e não conter expressões regulares com repetição aninhada (heurística contra ReDoS) nem com mais de 200 caracteres.
- **Forma canônica e hash.** Chaves em ordem, sem espaços, números normalizados, escape mínimo (estilo RFC 8785); o SHA-256 dessa forma identifica a versão. O mesmo conteúdo dá o mesmo hash, qualquer que seja a formatação.
- **Evolução.** O `SnapshotConverter` leva documentos antigos à versão corrente por passos `Migration` (n para n+1) sobre a árvore JSON, antes da validação; versão futura é recusada. Hoje só existe a versão 1, então não há passos; a regra é que mudar o formato exige um passo de migração e um schema novo.

## Consequências

- O motor (E11 e E12) não toca no JSON cru: recebe `Snapshot` e o hash vindos de `SnapshotService.parse`.
- Mudar o schema v1 depois de publicar versões é proibido; qualquer mudança incompatível cria a v2 com migração.
- A heurística de regex é uma barreira, não uma garantia: a validação do corpo em tempo de execução (E11) também roda com prazo.
- A biblioteca `liberty-snapshot` não depende de Spring nem de persistência (regra ArchUnit).

## Alternativas descartadas

- Modelo só em classes Java, sem JSON Schema: a interface (Monaco) e as ferramentas de validação não teriam contrato publicado.
- Guardar o hash sobre os bytes enviados: formatação diferente do mesmo conteúdo geraria versões "diferentes".
- Aceitar `$ref` remoto no schema do corpo com lista de hosts: superfície de SSRF sem necessidade no MVP.
