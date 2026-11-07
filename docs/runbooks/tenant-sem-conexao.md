# Um tenant recebe 503 `tenant-unavailable`

**Reconhecer:** respostas 503 com `type: urn:liberty:problem:tenant-unavailable` para um tenant; a publicação no editor também falha ao validar tabelas.

**Olhar primeiro**
1. Tela **Administração do tenant → Conexões → Testar conexão** (ou `POST /api/v1/platform/tenants/{slug}/connections/{env}/test`). A mensagem é genérica de propósito.
2. O banco do cliente está no ar e acessível da rede da aplicação? O usuário do banco tem login e permissão de `SELECT`, `INSERT`, `UPDATE` (sem `DELETE` nem DDL)?
3. A senha mudou? O cofre guarda a senha em `liberty/tenants/{slug}/{hml|prd}` (campo `password`).

**Correção**
- Senha trocada no banco do cliente: em **Conexões**, preencha a nova senha e salve (a senha vai direto ao cofre; salvar descarta o pool e o catálogo do tenant, então a próxima chamada já usa a nova).
- URL ou usuário errados: corrija na mesma tela. A URL precisa de `encrypt=true` e só aceita SQL Server.
- Banco do cliente fora: não há o que fazer do nosso lado; avise o cliente. As chamadas voltam sozinhas quando o banco voltar.

**Confirmar:** **Testar conexão** responde "Conexão estabelecida" e uma chamada de leitura da integração devolve 200.
