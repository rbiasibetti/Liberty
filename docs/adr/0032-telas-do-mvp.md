# 0032. Telas do MVP (parte 1: lista, editor, mapeamento, revisão)

- Status: aceito (E20 parcial: faltam as telas de auditoria e de administração do tenant)
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

## O que falta da E20

- **Auditoria:** consulta com filtros (período, integração, status, `request_id`) e gaveta com payload mascarado, SQL e erro. Hoje a auditoria guarda só metadados (ADR 0029); não há payload nem SQL gravados, nem endpoint de consulta.
- **Administração do tenant:** conexões HML e PRD com teste de conexão, clientes OAuth2 e usuários e papéis. Exige endpoints novos e escrita de segredos no cofre, que merecem etapa própria com revisão de segurança.
- A tela de mapeamento não edita transformações (`transforms`) nem consultas nomeadas: o integrador as vê no JSON do snapshot, mas ainda não as edita pela interface.

## Consequências

- Testado com provedor OIDC e API falsos (74 testes, 94% de linhas) e, no navegador, só o Monaco: o editor, o editor de diff, os workers e a checagem de tipos dos scripts foram exercitados numa página descartável. **As telas completas não foram exercitadas num navegador contra o backend e o Keycloak reais.**
- O bundle principal tem 780 kB (236 kB comprimido); o Monaco e seus workers são arquivos à parte, baixados sob demanda. Os workers saem como arquivos da mesma origem, compatíveis com o CSP atual.
