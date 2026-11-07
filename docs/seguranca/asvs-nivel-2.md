# OWASP ASVS 4.0, nível 2: lista de verificação

Estado por capítulo, com a evidência. É uma autoavaliação do time (não substitui o teste externo) e deve ser relida a cada etapa. **Atendido**: controle implementado e testado. **Parcial**: implementado com ressalva. **Pendente**: sem controle ou sem prova.

| Capítulo | Estado | Evidência e ressalvas |
| --- | --- | --- |
| V1 Arquitetura e modelagem de ameaças | Atendido | ADRs 0001 a 0032, [modelo de ameaças](modelo-de-ameacas.md). |
| V2 Autenticação | Parcial | OIDC com PKCE para usuários; `client_credentials` com segredo aleatório de 32 bytes e hash BCrypt para sistemas. MFA e política de senha são do provedor OIDC (fora do produto). |
| V3 Sessão | Parcial | Tokens de 15 minutos, renovação por refresh token, sessão na aba; sem revogação imediata de token de usuário (só de cliente, em até 15 s). |
| V4 Controle de acesso | Atendido | Papéis por tenant lidos a cada chamada, `@PreAuthorize` em todo endpoint (teste de meta), teste de acesso cruzado entre tenants em todos os endpoints, quatro olhos na publicação. |
| V5 Validação e codificação | Atendido | JSON Schema do corpo, tipos pelas colunas, parâmetros tipados no SQL, identificadores do catálogo, limites de corpo e de listas, JSON estrito. |
| V6 Criptografia | Parcial | RS256, chaves no Vault com rotação; BCrypt para segredos; TLS e `encrypt=true` exigidos para o banco do cliente. TLS da borda é do ingress (não validado aqui). |
| V7 Erros e registro | Parcial | Erros genéricos, log estruturado sem dado pessoal, máscara na auditoria; a trilha das ações administrativas é só log. |
| V8 Proteção de dados | Parcial | Segredos só no cofre, respostas sem senha, `Cache-Control: no-store` em respostas sensíveis; classificação formal de dados e política de retenção por tenant ainda não definidas (a retenção da auditoria é 13 meses). |
| V9 Comunicação | Parcial | HSTS, CSP e demais cabeçalhos configurados; a configuração de TLS do ambiente não faz parte do repositório. |
| V10 Código malicioso | Atendido | Scripts de integradores em processos isolados, sem rede nem arquivos; dependências com versão fixa, CodeQL e varredura de segredos no CI. |
| V11 Lógica de negócio | Atendido | Escrita atômica, idempotência, publicação com revisão e rollback, limites por plano. |
| V12 Arquivos e recursos | Atendido | Não há upload nem download de arquivos; corpo e listas com limite. |
| V13 API e serviços web | Atendido | OpenAPI da administração conferida contra os controladores, problem+json uniforme, CORS por lista de origens, métodos e tipos de conteúdo conferidos. |
| V14 Configuração | Parcial | Sem segredos no repositório (varredura no CI), imagens com digest, sem credencial padrão. Endurecimento da imagem de produção (distroless, sem root, assinatura) foi escrito mas **não está ativo** (sem pipeline de imagens por ora). |

## O que falta para fechar o nível 2

- Teste de penetração externo e varredura DAST (OWASP ZAP) contra um ambiente implantado.
- Trilha de auditoria em tabela para ações administrativas e de ciclo de vida.
- Pipeline de imagens (assinatura e SBOM) quando houver destino de implantação.
- Política de retenção e classificação de dados por tenant.
