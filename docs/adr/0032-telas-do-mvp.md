# 0032. Telas do MVP

- Status: aceito
- Data: 2026-11-07
- Etapa: E20

## Contexto

O plano prevê seis telas: lista de integrações, editor, mapeamento, revisão e publicação, auditoria e administração do tenant. As quatro primeiras cobrem o cenário de pronto do MVP (criar, testar no sandbox, enviar para revisão, publicar em HML, promover para PRD e voltar atrás); as duas últimas pedem endpoints de administração que ainda não existem.

## Decisão

**Entregues nesta etapa.**
- **Lista:** chave, rota, versão ativa em HML e em PRD, rascunho pendente e saúde das últimas 24 h (chamadas e erros 5xx vindos da auditoria); criação de integração.
- **Editor** com abas: rota e filtros (parâmetros, operação, tabela, colunas e filtros da leitura, idempotência e status de sucesso das escritas), JSON Schema (editor Monaco, geração a partir de um JSON de exemplo), mapeamento, scripts e testes, e o **sandbox** num painel lateral redimensionável (resultado, SQL e console).
- **Mapeamento:** árvore de até 5 níveis; tabela e colunas vêm do catálogo do banco do tenant em HML, os ponteiros JSON são sugeridos a partir do JSON Schema, "mapear pelo nome" preenche as colunas que casam com propriedades do corpo, cada lista do corpo vira um nível filho.
- **Revisão e publicação:** diff estrutural contra a versão ativa em HML (ou a anterior, ou a escolhida), diff de texto dos scripts no editor de diff do Monaco, execução dos casos de teste no sandbox, aprovar com comentário (publica em HML), rejeitar, promover para PRD, rollback e despublicar por ambiente. O botão de aprovar fica desligado para o autor da versão (o servidor repete a regra).

**Monaco.** Pacote local (nada de CDN, por causa do CSP), carregado só quando uma tela com editor abre. Os scripts recebem os tipos da API (`db`, `fail`, `BeforeInput`, `AfterInput`) mais o tipo do corpo gerado do JSON Schema da versão: o editor aponta, antes de salvar, erros como chamar `toFixed` num campo texto. As funções do usuário são tipadas por JSDoc (`@param {BeforeInput} input`), porque uma declaração global de `beforeExecute` conflitaria com a função do integrador; o tipo do corpo se chama `LibertyBody` porque `Body` colide com o `Body` do DOM.

**API.** O backend ganhou a lista enriquecida (versões ativas, rascunho e saúde) e o catálogo do tenant por ambiente (`GET .../environments/{env}/catalog/tables` e `.../tables/{tabela}`, papel INTEGRATOR). A especificação OpenAPI da administração e o cliente gerado foram atualizados, e o teste de deriva continua valendo.

**Estado, formulários, tabelas.** O plano citava TanStack Query, Zustand, React Hook Form e Zod. Aqui o estado do servidor usa um hook próprio mínimo (`useRequest`) e o do editor é um estado local por tela com o snapshot inteiro (o servidor valida tudo no salvamento e devolve violações por campo); trazer essas bibliotecas passa a valer quando a quantidade de telas justificar.

## Auditoria e administração do tenant

**Auditoria** (tela 5). `GET /api/v1/tenants/{slug}/audit/executions`, papel INTEGRATOR: período (padrão 24 h, no máximo 31 dias, porque a tabela é particionada por mês e a consulta precisa de janela), integração, status (500 ou 5xx), `request_id`, paginação de até 100 (a resposta diz se há próxima página). Os filtros são validados por padrão e viram parâmetros do SQL. A tela mostra a lista e uma gaveta de detalhes com tempos, cliente, código e mensagem de erro. **A gaveta não tem payload nem SQL**: a auditoria guarda só metadados e a mensagem de erro mascarada (ADR 0029), então o que o plano descrevia como "payload mascarado e SQL" fica no sandbox (ADR 0028), que mostra o SQL de uma versão. A guarda do payload mascarado (`int_audit_payload`) continua para uma etapa futura, com amostragem e política de retenção próprias.

**Administração do tenant** (tela 6), só para o administrador da plataforma (papel do provedor OIDC, nunca do banco), sob `/api/v1/platform/tenants/{slug}`:
- **Conexões** de HML e PRD: a URL passa pela mesma política dos pools (só SQL Server, criptografia obrigatória, sem propriedades perigosas); a senha é gravada direto no Vault (`liberty/tenants/{slug}/{env}`) e nunca volta em resposta nem aparece em log; atualizar sem senha mantém a do cofre; salvar descarta o pool e o catálogo do tenant. "Testar conexão" abre uma conexão de verdade com os dados salvos (senha lida do cofre) e roda `SELECT 1`, com prazo de 5 s, devolvendo mensagens genéricas (nada de texto do driver).
- **Clientes OAuth2**: criar (o segredo, 32 bytes aleatórios, aparece uma única vez na resposta e só o hash é guardado) e revogar (a revogação vale em até 15 s, a janela do cache); escopos restritos a `integration:execute:<chave>`; um administrador não revoga cliente de outro tenant.
- **Usuários e papéis**: conceder e retirar `VIEWER`, `INTEGRATOR` e `PUBLISHER`; vale na hora, porque os papéis são lidos a cada requisição.

**Correção de fuso (E17).** O carimbo da auditoria era gravado como `Timestamp` e dependia do fuso da JVM; passa a ser a hora UTC como `LocalDateTime`, igual ao `SYSUTCDATETIME()` do padrão da coluna, e as consultas usam UTC.

## O que ainda não está na interface

- Transformações de campo (`transforms`) e consultas nomeadas só existem no JSON do snapshot, sem edição pela interface.
- Edição de clientes já criados (escopos, validade) e troca de senha de um cliente: hoje se revoga e se cria outro.

## Consequências

- Testado com provedor OIDC e API falsos (82 testes, 94% de linhas) e, no navegador, só o Monaco: o editor, o editor de diff, os workers e a checagem de tipos dos scripts foram exercitados numa página descartável. **As telas completas não foram exercitadas num navegador contra o backend e o Keycloak reais**; o backend de auditoria e de administração foi testado de ponta a ponta (cofre falso que guarda o que recebe, conexão de teste de verdade contra o SQL Server, segredo de cliente que autentica e depois é revogado).
- O bundle principal tem 780 kB (236 kB comprimido); o Monaco e seus workers são arquivos à parte, baixados sob demanda. Os workers saem como arquivos da mesma origem, compatíveis com o CSP atual.
