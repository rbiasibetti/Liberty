# Registros de decisão de arquitetura (ADR)

Toda decisão que muda a arquitetura, a segurança ou uma dependência estrutural vira um ADR neste diretório. Para mudar uma decisão aceita, crie um novo ADR que a substitua e atualize o status do antigo para `substituído por NNNN`.

Formato: copie [template.md](template.md), use o próximo número e abra um PR.

| ADR | Proposta v2 | Título |
| --- | --- | --- |
| [0001](0001-identificacao-do-tenant-pelo-jwt.md) | D1 | Identificação do tenant pelo JWT |
| [0002](0002-metadados-no-banco-central.md) | D2 | Metadados no banco central |
| [0003](0003-dados-de-negocio-no-banco-do-tenant-so-dml.md) | D3 | Dados de negócio no banco do tenant, só DML |
| [0004](0004-sql-server-2019-como-unico-sgbd.md) | D4 | SQL Server 2019+ como único SGBD |
| [0005](0005-acesso-dinamico-com-namedparameterjdbctemplate.md) | D5 | Acesso dinâmico com NamedParameterJdbcTemplate |
| [0006](0006-datasource-do-core-separado-do-roteado.md) | D6 | DataSource do core separado do roteado |
| [0007](0007-graaljs-pela-api-polyglot-como-unico-motor-de-script.md) | D7 | GraalJS pela API Polyglot como único motor de script |
| [0008](0008-mapeamento-como-arvore-de-nos.md) | D8 | Mapeamento como árvore de nós |
| [0009](0009-operacoes-suportadas-no-mvp.md) | D9 | Operações suportadas no MVP |
| [0010](0010-versionamento-por-snapshot-imutavel.md) | D10 | Versionamento por snapshot imutável |
| [0011](0011-redis-para-estado-compartilhado.md) | D11 | Redis para estado compartilhado |
| [0012](0012-object-storage-para-arquivos.md) | D12 | Object storage para arquivos |
| [0013](0013-micrometer-prometheus-e-opentelemetry.md) | D13 | Micrometer, Prometheus e OpenTelemetry |
| [0014](0014-react-typescript-mantine-e-monaco-no-frontend.md) | D14 | React, TypeScript, Mantine e Monaco no frontend |
| [0015](0015-segredos-em-cofre.md) | D15 | Segredos em cofre |
| [0016](0016-java-25-lts-e-spring-boot-4-1.md) | T1 | Java 25 LTS e Spring Boot 4.1 |
| [0017](0017-scripts-em-processos-trabalhadores.md) | D7 | Scripts de integradores em processos trabalhadores |
| [0018](0018-slos-e-capacidade.md) | E05 | SLOs e capacidade |
| [0019](0019-persistencia-do-core.md) | E06 | Persistência do banco central |
| [0020](0020-tenancy-em-tempo-de-execucao.md) | E07 | Tenancy em tempo de execução |
| [0021](0021-autenticacao-e-autorizacao.md) | E08 | Autenticação e autorização |
| [0022](0022-catalogo-do-tenant-e-identificadores-sql.md) | E09 | Catálogo do tenant e identificadores SQL |
| [0023](0023-modelo-do-snapshot.md) | E10 | Modelo do snapshot de integração |
| [0024](0024-motor-de-leitura.md) | E11 | Motor de leitura e roteamento |
| [0025](0025-motor-de-escrita.md) | E12 | Motor de escrita e idempotência |
| [0026](0026-motor-de-scripts.md) | E14 | Motor de scripts em processos trabalhadores |
| [0027](0027-ciclo-de-vida-das-integracoes.md) | E15 | Ciclo de vida das integrações |
| [0028](0028-sandbox.md) | E16 | Sandbox de execução |
| [0029](0029-auditoria-e-observabilidade.md) | E17 | Auditoria e observabilidade |
| [0030](0030-protecao-de-trafego.md) | E18 | Proteção de tráfego |
| [0031](0031-frontend-base.md) | E19 | Frontend base |
