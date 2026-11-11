# 0038. Provisionamento de tenants

- Status: aceito
- Data: 2026-11-10
- Etapa: A09 da fase 3

## Contexto

O plano diz que o administrador da plataforma provisiona tenants e gerencia planos, mas só existia a leitura: o tenant era uma linha inserida por SQL (nos testes, por repositório). Sem isso, nenhum tenant novo nasce sem acesso ao banco central, e não havia como saber se um tenant recém-configurado estava pronto.

## Decisão

**API de plataforma** (só `PLATFORM_ADMIN`, em `/api/v1/platform/tenants`):
- `GET` lista os tenants por identificador; `POST` cria; `PATCH /{slug}` altera nome, plano e estado; `GET /{slug}/onboarding` devolve o checklist.
- **Criação:** valida o slug com a mesma regra do domínio (`TenantId`: 2 a 63 caracteres, minúsculas, números e hífen, não reservado; o banco impõe a lista reservada), nome de até 200 caracteres e plano conhecido (`STANDARD` mais os configurados em `liberty.limits.plans`). Duplicado é 409. O tenant nasce ativo, **sem** conexões, usuários nem clientes: a plataforma não cria o banco do cliente (ADR 0020).
- **Estado:** `ACTIVE`, `SUSPENDED`, `ARCHIVED`. Suspender ou arquivar vale na hora: usuários (papéis lidos a cada decisão), clientes de sistema (cache de 15 s), conexões e rotas já respeitam o estado, e a alteração ainda derruba os pools e a tabela de rotas do tenant em memória. **Arquivado não volta e não pode ser alterado** (409).
- **Plano:** o limite de taxa lê `int_tenant.plan_code` com cache de 1 minuto.

**Checklist de onboarding**, cada item com estado `OK`, `PENDING`, `WARN` ou `FAIL` e o que fazer: tenant ativo; para HML e PRD, conexão cadastrada, **testada** (`SELECT 1`) e com **privilégios conferidos**; quem escreve e quem aprova (dois usuários diferentes com INTEGRATOR e PUBLISHER); pelo menos um cliente OAuth2 ativo. `ready` é verdadeiro quando nada está pendente ou falho; **avisos não bloqueiam**.

**Privilégios excessivos** (também fecha o C11 do backlog): o usuário do banco do cliente é conferido contra `sysadmin`, `securityadmin`, `serveradmin`, `db_owner`, `db_ddladmin` e `db_securityadmin`. Quem tem algum deles pode, por exemplo, habilitar `xp_cmdshell` ou criar gatilhos com efeito externo; o checklist avisa e pede um usuário só com leitura e escrita nas tabelas das integrações. Se não for possível verificar, o aviso diz isso. Os testes cobrem um usuário administrativo e um limitado, criados de verdade no SQL Server.

**Interface:** a tela "Tenants" (lista, novo tenant, editar plano e estado com aviso de que arquivar é definitivo, onboarding) e o botão "Administrar", que abre a administração do tenant já selecionado (`/administracao?tenant=slug`).

## Consequências

- Criar um tenant, configurá-lo e conferi-lo não exige mais SQL manual. Há um E2E dessa jornada.
- O plano é texto livre validado no servidor: não há API para definir os planos nem os limites, que continuam em configuração (C12 do backlog).
- As ações ficam só no log (A10 do backlog): ainda não há trilha persistente de quem criou, suspendeu ou arquivou.
- Não há exclusão física de tenant: o fim de vida é `ARCHIVED`, que mantém integrações, versões e auditoria (sem `ON DELETE CASCADE`).
