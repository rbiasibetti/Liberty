# Uma publicação deu problema em produção

**Voltar à versão anterior (menos de 1 minuto):** na tela **Revisão e publicação** da versão com problema, use **Rollback em PRD** (ou `POST /api/v1/tenants/{slug}/integrations/{key}/environments/PRD/rollback`). A publicação anterior volta a valer na hora na instância que atendeu e em até 5 segundos nas demais. Repetir o rollback desfaz o rollback.

**Tirar a integração do ar:** **Despublicar de PRD** (`DELETE .../environments/PRD/publication`). Os consumidores passam a receber 404 da rota.

**Um cliente de sistema comprometido:** **Administração do tenant → Clientes OAuth2 → Revogar** (a revogação vale em até 15 segundos). Crie outro cliente e entregue o novo segredo por canal seguro; o segredo só aparece uma vez.

**Depois:** abra a versão publicada na auditoria (`integration` e janela de tempo) para medir o estrago e registre no ADR ou na retrospectiva o que as conferências de publicação deixaram passar.

**Lembretes:** o autor da versão não pode aprovar nem publicar a própria; PRD só aceita versões já publicadas em HML.
