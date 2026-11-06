# 0020. Tenancy em tempo de execução

- Status: aceito
- Data: 2026-11-06
- Etapa: E07

## Contexto

O banco do cliente é escolhido por requisição (ADR 0001 e 0006). A proposta original usava um `AbstractRoutingDataSource` estático, `DriverManagerDataSource` e senha no código, e o contexto de tenant se perdia em tarefas assíncronas.

## Decisão

- `TenantContext` e `TenantScope` (tenant e ambiente) vivem no domínio, em Java puro. `require()` falha sem tenant: não existe tenant padrão.
- `TenantPoolRegistry`: um pool Hikari por tenant e ambiente, criado na primeira requisição, em cache Caffeine com expiração só por inatividade (30 minutos), sem limite de tamanho. Pool com conexão em uso não é fechado na expiração. Falhas de criação não ficam em cache. Pools têm `minimumIdle=0`, `connectionTimeout` de 5 s, tamanho máximo do plano do tenant e `applicationName` com tenant e ambiente.
- A senha vem do Vault (KV v2, campo `password`), pela referência guardada em `int_tenant_connection.secret_ref`; autenticação Kubernetes em produção e token só em desenvolvimento. A referência é validada contra um padrão estrito (sem `..`, `?`, `#`).
- A URL JDBC vinda do cadastro é revalidada antes de abrir o pool: só SQL Server, `encrypt=true` ou `strict`, e sem propriedades do driver que carregam código ou arquivos (`socketFactoryClass`, `trustStore`, `authenticationScheme` etc.).
- `TenantRoutingDataSource` estende `AbstractDataSource`, sem destino padrão, e não é um bean: só o core é `DataSource` bean (primário), então os health checks e a autoconfiguração nunca tocam o roteador. O motor usa `tenantJdbcTemplate`, `tenantTransactionManager` e `tenantTransactionTemplate`, todos baseados na mesma instância do roteador; o `transactionManager` primário (JPA) continua sendo o do core.
- `TenantContextTaskDecorator` leva o tenant para a thread da tarefa e o limpa ao fim; tarefa sem tenant executa sem tenant. O executor `applicationTaskExecutor` da aplicação o usa.

## Consequências

- O contexto do tenant precisa ser definido antes de abrir uma transação de tenant (a conexão é obtida no início).
- O filtro de requisição que lê o JWT e chama `TenantContext.set` é da E08.
- Mudar conexão ou senha de um tenant exige `TenantPoolRegistry.evict(scope)`; a administração (E20) chama isso.
- Métricas de pools (ativos, pendentes, tempo de espera) entram na E17.
- O teste de 50 tenants cria 50 bancos e leva cerca de 1 minuto; fica no build por ser o critério de aceite da etapa.

## Alternativas descartadas

- `AbstractRoutingDataSource` com mapa estático: não cria pools sob demanda nem os descarta.
- Registrar o roteador como bean `DataSource`: o health check do banco falharia sem tenant no contexto.
- Spring Cloud Vault para toda a configuração: o escopo é só a senha do tenant, e a dependência seria desproporcional.
