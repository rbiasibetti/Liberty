# 0027. Ciclo de vida das integrações

- Status: aceito
- Data: 2026-11-07
- Etapa: E15

## Contexto

Integrações são metadados que viram APIs em produção. Precisam de autoria rastreável, revisão por outra pessoa, publicação separada por ambiente (HML e PRD) e volta rápida para a versão anterior.

## Decisão

**Estados.** Uma versão nasce `DRAFT`, vai a `IN_REVIEW` e termina `PUBLISHED` ou `REJECTED`; depois disso é imutável (gatilho `trg_int_version_guard`, ADR 0019). Só existe um rascunho por integração; salvar de novo o substitui. O snapshot é guardado na forma canônica, com o SHA-256.

**Papéis por tenant** (hierarquia VIEWER < INTEGRATOR < PUBLISHER, conferida a cada chamada): VIEWER consulta; INTEGRATOR cria integrações, salva rascunhos e envia para revisão; PUBLISHER rejeita, publica, reverte e despublica.

**Quatro olhos.** Quem escreveu a versão (`author_sub`) não a rejeita nem a publica, nem em HML nem em PRD (403 `four-eyes`); o banco repete a regra (`reviewer_sub <> author_sub`).

**Publicação por ambiente.** Uma versão `IN_REVIEW` vira `PUBLISHED` na primeira publicação (o publicador fica como revisor, com comentário); PRD só aceita versões já publicadas em HML (409 `not-in-hml`). Publicar desativa a publicação ativa do ambiente e cria a nova (a desativação vai ao banco antes, por causa do índice único de publicação ativa). O histórico de publicações nunca é apagado. Valem na hora na instância que publica (`invalidate`) e em até 5 s nas demais (ADR 0024).

**Rollback.** Reativa a publicação anterior com outra versão no mesmo ambiente, por uma nova linha de publicação (o histórico mostra o rollback); sem versão anterior, 409. Repetir o rollback desfaz o rollback. Não exige outro revisor, para servir em emergência. **Despublicar** desativa a publicação do ambiente.

**Conferências.** Ao salvar e ao enviar para revisão: o snapshot passa no esquema e nas regras semânticas (E10), chave, método e rota são os do cadastro, `beforeExecute` não existe em leitura e as consultas nomeadas são um único `SELECT`/`WITH`. Ao publicar: as tabelas citadas existem no banco do ambiente (catálogo do tenant) e nenhuma outra integração ativa no ambiente atende a mesma rota estrutural e método. Falhas voltam em 422 `publish-checks` com uma violação por problema.

**OpenAPI.** `GET /api/v1/tenants/{slug}/environments/{env}/openapi.json` gera um OpenAPI 3.1 das integrações publicadas no ambiente (parâmetros, corpo, `Idempotency-Key`, status de sucesso, erros `problem+json`, OAuth2 `client_credentials` com um escopo por integração).

**API** (usuários OIDC, sob `/api/v1/tenants/{slug}`): `GET|POST /integrations`, `GET /integrations/{key}/versions`, `GET .../versions/{no}/snapshot`, `PUT /integrations/{key}/draft`, `POST .../versions/{no}/submit|reject|publish/{env}`, `POST .../environments/{env}/rollback`, `DELETE .../environments/{env}/publication`.

## Consequências

- **Casos de teste do snapshot** (`tests`) são validados na estrutura (E10), mas ainda **não são executados** na publicação; exigir que passem fica para a E19/E20, junto da interface.
- A trilha de auditoria das ações do ciclo de vida é, por ora, o log da aplicação (usuário, integração, versão, ambiente); a tabela de auditoria entra na E17.
- Cada ação roda numa transação do banco central; publicar não valida o schema do banco do cliente além de existirem as tabelas (colunas são conferidas na execução, ADR 0022).
