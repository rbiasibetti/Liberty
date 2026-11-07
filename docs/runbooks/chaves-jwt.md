# Chaves de assinatura dos tokens

**Rotina:** o chaveiro (Vault KV v2, `liberty/jwt/keys`) rotaciona sozinho a cada 90 dias, com 24 horas de convivência da chave antiga; o JWKS publica só a parte pública. A rotação automática roda a cada 5 minutos (verificação) e às 03:17.

**Chave vazada ou suspeita (rotação de emergência)**
1. Gere uma chave nova pelo caminho do chaveiro (a próxima verificação a ativa; reinicie uma instância para forçar na hora).
2. Retire a chave comprometida do chaveiro: tokens assinados por ela deixam de valer na hora; os clientes pedem token novo (duram 15 minutos de qualquer jeito).
3. Revogue os clientes OAuth2 que possam ter sido expostos (**Administração do tenant → Clientes**).
4. Investigue o acesso ao Vault; troque o token ou o papel de acesso da aplicação.

**Confirmar:** `GET /.well-known/jwks.json` lista só chaves vigentes e uma chamada autenticada com token novo responde 200.
