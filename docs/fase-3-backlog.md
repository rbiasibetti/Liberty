# Fase 3: backlog de evolução

Levantamento das limitações que ficaram conhecidas ao fim das etapas E01 a E24, com a medida necessária para cada uma. Segue a regra do plano: **os itens da fase 3 viram etapas próprias só depois do Portão 3**, no mesmo formato da tabela de etapas. Até lá, este documento é a fila.

A E25 (painel de saúde e alertas por WebSocket) continua sendo da fase 2 e não está aqui.

## Como ler

- **Origem:** onde a limitação está registrada (ADR, portão, modelo de ameaças) ou como foi descoberta.
- **Bloco A** não é evolução: são pendências que impedem ou enfraquecem o uso em produção. Vêm primeiro.
- Tamanho: **P** (dias), **M** (uma a duas semanas), **G** (mais que isso ou depende de terceiros).

## Proposta de Portão 3

Todo o bloco A concluído, a E25 entregue e o piloto em PRD rodando sem incidente grave por quatro semanas. O resto do backlog é priorizado a partir do que o piloto mostrar.

---

## Bloco A: pendências de liberação

| ID | Limitação hoje | Origem | Medida necessária | Pronto quando | Tam. |
| --- | --- | --- | --- | --- | --- |
| A01 | **Scripts, `http.fetch` e `queue.publish` não funcionam na imagem de produção.** A imagem roda `java -jar` de um jar executável do Spring Boot (jars aninhados) e o trabalhador de script usa o classpath da aplicação como `-cp`. Nunca foi testada a imagem | ADR 0026; `backend/Dockerfile` | Empacotar com classpath explodido (camadas) ou apontar `liberty.scripts.worker-classpath` para os jars; teste de fumaça que **sobe a imagem** e executa um script no sandbox | A imagem executa um script, uma chamada `http.fetch` simulada e uma publicação simulada | P |
| A02 | Imagens e manifests nunca foram construídos nem aplicados (sem Docker nem cluster aqui); o orquestrador segue decisão em aberto | Plano, questões em aberto; `deploy/` | Decidir o orquestrador; construir as imagens na esteira, aplicar `deploy/k8s` em homologação, conferir probes, limites de memória (`poolSize × (heap + 100 MB)`) e criar as políticas de rede (os manifestos atuais não têm `NetworkPolicy`) | Homologação de pé, atualizada pela esteira por digest | M |
| A03 | **Carga nunca foi executada.** Os cenários k6 existem; a medição em processo mostrou o vizinho barulhento de 35 para 74 ms, acima do critério de 20% | `docs/portao-2.md` (critério 5) | Rodar `escrita`, `leitura`, `vizinho-barulhento` contra homologação e registrar os p95; ajustar pools, limites e bulkheads até cumprir | Critério 5 do MVP medido e registrado | M |
| A04 | DAST (OWASP ZAP) não executado | Portão 2 | ZAP em homologação autenticada (cliente OAuth2 e usuário), com a especificação OpenAPI como entrada | Relatório sem achados altos abertos | P |
| A05 | Pentest externo não contratado | Portão 2; `docs/seguranca/` | Contratar; entregar modelo de ameaças e checklist ASVS nível 2; corrigir e retestar | Relatório final com achados altos fechados | G |
| A06 | Nenhum integrador real usou a interface sem ajuda; nenhum cliente em PRD | Portão 2 (critério 1) | Sessão de usabilidade observada e piloto com um cliente | Critério 1 cumprido e piloto iniciado | G |
| A07 | A interface só foi exercitada com um provedor OIDC **falso** (PKCE sim; claims, renovação silenciosa, logout e papel de plataforma não). A CSP do nginx precisa da origem do provedor | ADR 0033; `frontend/nginx.conf` | Rodar o E2E contra o Keycloak do `deploy/local/keycloak` (e o provedor real do cliente); ajustar CSP; testar renovação e expiração | E2E passa com Keycloak real | M |
| A08 | O Vault só foi testado contra um **servidor falso** (leitura e escrita de KV v2, chaves JWT com `store: VAULT`) | ADR 0026; testes `*ContractTest` | Testes de integração contra um Vault real (modo dev em contêiner na esteira); rotação de chaves JWT ponta a ponta | Suíte passa contra Vault real, rotação demonstrada | M |
| A09 | **Não existe API nem tela para criar tenant**, trocar o plano, suspender ou arquivar: o tenant é uma linha inserida por SQL (nos testes, por repositório). O limite de taxa já lê `int_tenant.plan_code` | Plano (administrador provisiona tenants e planos) | Endpoints de plataforma `POST /platform/tenants`, `PATCH` (plano, estado), checklist de onboarding (usuário do banco com privilégio mínimo, sem `xp_cmdshell`, conexão testada) | Tenant novo criado, configurado e usado sem SQL manual | M |
| A10 | As ações administrativas (conexões, clientes, papéis, hosts de saída, mortas da fila, publicações, remoções) ficam **só no log** | `TenantAdminService`, `QueueAdmin`; ADR 0036 | Tabela de trilha de administração no core (quem, quando, o quê, tenant; sem segredos), consulta por API, retenção | Toda ação administrativa deixa linha consultável | M |
| A11 | O E2E não roda na esteira (precisa de SQL Server e Chrome); o teste do Redis real é opcional; a esteira não varre dependências desde que o OWASP Dependency-Check saiu (exigia chave do NVD) | ADR 0033; `ci.yml` | SQL Server como serviço e Chrome no CI para o E2E; Redis como serviço; varredura de dependências sem chave (OSV-Scanner ou equivalente) e das imagens; SBOM; assinatura de imagem | Esteira roda E2E, Redis e varredura e falha em achado alto | M |
| A12 | O banco de desenvolvimento local não tem as migrações V3 a V6 (as dos testes são aplicadas em bancos descartáveis) | Observação de sessão | Documentar e automatizar `make migrate-core` na subida local; pipeline de migração do core em cada ambiente | `local-up` deixa o core na versão atual | P |

---

## Bloco B: robustez e operação

| ID | Limitação hoje | Origem | Medida necessária | Pronto quando | Tam. |
| --- | --- | --- | --- | --- | --- |
| B01 | O caminho do Redis (limite de taxa somado entre instâncias) só tem o teste da alternativa em memória | ADR 0030 | Testes com Redis real: queda, latência, reconexão e recuperação para o limite local | Comportamento de falha documentado e testado | P |
| B02 | Transformações de campo sem testes de propriedade | ADR 0025 | Testes baseados em propriedades (idempotência de `trim`, ida e volta de `toDate`/`toDecimal`, entradas hostis) | Suíte de propriedades na esteira | P |
| B03 | Os casos de teste do snapshot (`tests`) só rodam quando alguém aperta o botão na tela de revisão; a publicação não os exige | ADR 0032 | Executar os casos no sandbox como **portão de publicação** (falhou, não publica), com resultado registrado na versão | Publicar com caso falhando é recusado | M |
| B04 | Painel do Grafana, regras de alerta e rastros OpenTelemetry nunca foram vistos funcionando (sem Docker); não há métrica nem alerta de **atraso da fila** nem de chamadas de saída | ADR 0029; `deploy/local` | Subir a pilha de observabilidade em homologação, validar regras com `promtool`, painéis de fila (idade da mensagem mais antiga, mortas) e de `http.fetch` por host (sem tag de tenant, para não explodir a cardinalidade) | Painéis e alertas validados com carga real | M |
| B05 | Política de backup (log a cada 15 min, RPO ≤ 15 min, RTO 1 h) é texto: só o backup completo e a restauração foram exercitados. O armazenamento de exportações não tem backup | `restaurar-core.md`; `BackupRestoreTest` | Backup de log e restauração até um instante (`STOPAT`) automatizados e ensaiados todo mês; alta disponibilidade do core (grupo de disponibilidade ou equivalente) | Ensaio mensal registrado dentro do RTO e do RPO | G |
| B06 | Testado só no SQL Server Express local (sem compressão, edição única) | `BackupRestoreTest` | Matriz de versões e edições na esteira (2019, 2022, Standard; Azure SQL se for alvo): partições, `READPAST`, compressão | Suíte de banco passa em toda a matriz | M |
| B07 | Chave das URLs assinadas é única, sem identificador: trocá-la invalida todos os links, e vale uma só por vez | ADR 0036 | Identificador de chave (`kid`) no token e lista de chaves ativa para rotação sem corte | Rotação sem invalidar links em vida | P |
| B08 | O limite de taxa vale só para as rotas de execução; as de administração e o `http.fetch` não têm limite próprio além do teto por execução | ADR 0030, 0034 | Limite por usuário e por tenant nas rotas de administração; limite de chamadas de saída por tenant por minuto | Excesso responde 429 e é contado | M |
| B09 | Escala nunca foi dimensionada: poços de conexão por tenant, trabalhadores de script (memória), consumidores de fila, 100+ tenants | ADR 0020, 0026 | Teste de carga com 100+ tenants ativos; autoescala horizontal; dimensionamento documentado | Capacidade por instância medida e publicada | M |
| B10 | A fila no banco tem teto de vazão e contenção entre instâncias desconhecidos | ADR 0035 | Teste com várias instâncias consumindo; medir mensagens por segundo; critério para migrar o armazenamento atrás de `QueueRepository` (broker) | Teto medido e limiar de troca definido | M |
| B11 | **A publicação de mensagens não é atômica com o banco do cliente**: uma queda entre o commit e a gravação perde a mensagem | ADR 0035 | Padrão de caixa de saída opcional por integração (linha de evento pendente no banco do tenant, lida e publicada por um consumidor) | Integração pode exigir publicação garantida | G |
| B12 | **Auditoria sem entrega garantida**: a fila em memória descarta sob sobrecarga, com contagem e alerta | ADR 0029, 0035 | Critérios para reabrir (exigência contratual); gravação síncrona leve no core, medindo o custo no p95 | Decisão revisada com números | M |
| B13 | Exportações em disco local: várias instâncias exigem volume compartilhado; sem cota de armazenamento por tenant | ADR 0036 | Cota por tenant; limpeza de órfãos; migrar para objeto (C01) antes de escalar horizontalmente | Cota aplicada e limpeza verificada | P |

---

## Bloco C: evolução do produto

| ID | Limitação hoje | Origem | Medida necessária | Pronto quando | Tam. |
| --- | --- | --- | --- | --- | --- |
| C01 | O armazenamento é só em disco: **o adaptador S3-compatível não foi implementado**, por não haver como testá-lo | ADR 0036 | Adaptador atrás de `ObjectStore` com URL pré-assinada nativa (SigV4); testes com MinIO em contêiner | Exportação e download funcionam em S3 | M |
| C02 | Exportação só em CSV; paginação por deslocamento (teto de 100 mil linhas, não consistente se os dados mudam) | ADR 0036 | XLSX (biblioteca de escrita em fluxo); exportação por faixa de chave com leitura consistente; ordenação estável exigida | Mais de 100 mil linhas exportadas de forma consistente | M |
| C03 | Faltam telas: exportações, filas e mortas, hosts de saída, provisionamento de tenants, trilha de administração | ADR 0034, 0035, 0036 | Telas de administração com a mesma estrutura das atuais; na DLQ, prévia **mascarada** do conteúdo | Cada fluxo operável sem chamar a API à mão | M |
| C04 | `http.fetch`: sem respostas simuladas configuráveis no sandbox (scripts que ramificam pela resposta não são exercitados); lista de hosts única para HML e PRD, sem curinga; sem mTLS nem CA própria; **sem cofre de segredos para scripts** (credencial de API de saída teria de ir no script) | ADR 0034 | Respostas simuladas por caso de teste; lista por ambiente; curinga opcional; certificado de cliente; `secrets.get(nome)` com referência ao Vault, nunca o valor no snapshot; regra de rede de saída no cluster como defesa extra | Script chama API autenticada do cliente sem segredo no código | G |
| C05 | Uma só fila (`webhook`) e uma de exportação; consumidores só os do motor; sem ordenação, sem prioridade | ADR 0035 | Avaliar consumidores definidos pelo tenant; ordenação por chave quando houver caso real; prioridade | Caso real de tenant atendido | M |
| C06 | `DELETE` sem imagem do registro removido, sem exclusão lógica, aprovação só na publicação | ADR 0036 | Guardar a imagem anterior da linha (cifrada, com retenção) para desfazer; exclusão lógica opcional; aprovação de duas pessoas na execução em PRD para integrações marcadas | Remoção em PRD reversível dentro de uma janela | M |
| C07 | Exportar e importar uma versão entre instâncias (arquivo JSON assinado) estava no plano e não foi feito | Plano | Exportar o snapshot com hash e assinatura; importar como rascunho com todas as validações | Versão levada de uma instância a outra sem edição | M |
| C08 | Auditoria sem busca por texto no conteúdo (só metadados são gravados, por desenho) | Plano, auditoria | Decidir com o piloto: busca completa (OpenSearch) com mascaramento forte, ou manter só metadados | Decisão registrada | G |
| C09 | Só SQL Server, só JSON/REST; bancos que a plataforma não alcança pela rede exigem agente | Plano, questões em aberto | Avaliar o segundo banco (PostgreSQL), XML/SOAP e um agente no cliente, cada um só com caso de uso real | Primeiro caso real priorizado | G |
| C10 | Interface: sem internacionalização, sem auditoria de acessibilidade, só uma jornada no E2E; estado do servidor com hook próprio; TypeScript preso na 5.9 pelo `openapi-typescript`; Monaco pesa no pacote | ADR 0031, 0032 | Auditoria com axe e teclado; ampliar o E2E (administração, auditoria, rejeição, remoção, erros do sandbox); biblioteca de consultas quando as telas crescerem; subir o TypeScript quando o gerador permitir; carregar o Monaco só nas telas que o usam (já feito: medir) | Jornadas críticas cobertas e nota de acessibilidade registrada | M |
| C11 | Um gatilho do cliente com efeito externo (por exemplo `xp_cmdshell`) fica fora do controle da plataforma | `modelo-de-ameacas.md` ("falta") | No teste de conexão, conferir as permissões do usuário do banco e recusar ou avisar se tiver privilégio perigoso | Teste de conexão aponta o privilégio excessivo | P |
| C12 | Planos fixos em configuração; sem medição de uso por tenant | ADR 0030 | Planos e limites por API; contagem de uso (execuções, linhas, exportações) para cobrança e cotas | Relatório de uso por tenant e plano alterável | M |
| C13 | Scripts em GraalVM Community, sem JIT, com limite de memória só por processo; sem depuração | ADR 0017, 0026 | Medir; avaliar Oracle GraalVM (limites nativos) se o desempenho ou o risco exigirem; console e rastro de execução mais ricos no editor | Decisão baseada em medição | M |
| C14 | A API de administração não tem política de versão e depreciação nem cliente público | `docs/openapi/admin-api.yaml` | Política de versionamento e depreciação; geração de SDK a partir da especificação | Mudança incompatível passa por depreciação documentada | P |

---

## O que já dá para testar hoje

`scripts/demo.sh` sobe um MVP local para teste manual de todos os fluxos (ver [demo-local.md](demo-local.md)). Ele **não** substitui os itens do bloco A: usa o classpath de testes, OIDC e Vault falsos e dados descartáveis.

## Ordem sugerida

1. **A01, A12** (dias, desbloqueiam testar a imagem de verdade).
2. **A02, A07, A08, A09, A11** em paralelo: infraestrutura de homologação, Keycloak e Vault reais, provisionamento e esteira.
3. **A03, A04** assim que a homologação existir; **A10** em paralelo.
4. **A05, A06**: terceiros e cliente, com prazo próprio; começar a contratar cedo.
5. Depois do piloto: bloco B por risco observado e bloco C por demanda.

## Fora do escopo desta lista

Não há itens de "refatoração geral": cada medida acima nasce de uma limitação registrada. O que for descoberto no piloto entra aqui com origem e critério de pronto, no mesmo formato.
