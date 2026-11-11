# MVP local para testes

Para testar o produto à mão, sem Docker, numa máquina com SQL Server local:

```bash
scripts/demo.sh        # vale 120 minutos; Ctrl+C para parar
```

Exige `.env.local` (veja `.env.example`), Java 25, Node 22 ou superior no `PATH` e o SQL Server de teste acessível.

## O que sobe

- A **API real** (porta 18080) sobre um banco central e um banco de cliente **descartáveis**, que somem ao parar.
- A interface (porta 5173).
- Um **provedor OIDC falso** (porta 18081) que exige PKCE de verdade e aprova o login sozinho, e um **Vault falso**.
- O tenant `acme`, com conexões HML e PRD apontando para o banco de exemplo, que tem `dbo.tb_pedido` e `dbo.tb_pedido_item` com dados.

## Como usar

Escolha **quem você é antes de clicar em Entrar**, abrindo a URL do usuário:

| Usuário | URL | Faz |
| --- | --- | --- |
| `integrador` | `http://127.0.0.1:18081/__user?sub=integrador` | cria e mapeia integrações, testa no sandbox, envia para revisão |
| `aprovador` | `http://127.0.0.1:18081/__user?sub=aprovador` | revisa, publica em HML e PRD, faz rollback |
| `admin` | `http://127.0.0.1:18081/__user?sub=admin` | administra o tenant: conexões, clientes OAuth2, usuários, hosts de saída |

Depois abra `http://127.0.0.1:5173` e clique em Entrar. Para trocar de usuário, abra a URL do outro, use Sair e Entrar de novo. O autor de uma versão não a aprova: use `integrador` para escrever e `aprovador` para publicar.

Roteiro sugerido: como `integrador`, crie uma integração `POST /pedidos`, gere o schema a partir de um exemplo, mapeie `dbo.tb_pedido` com a lista `itens` em `dbo.tb_pedido_item`, execute no sandbox e envie para revisão; como `aprovador`, publique em HML e promova para PRD; como `admin`, crie um cliente OAuth2 e chame `http://127.0.0.1:18080/acme/api/pedidos` com o token dele.

## Limites desta demonstração

- Roda pelo classpath de testes do Maven, **não pela imagem de produção**: o empacotamento dos scripts na imagem ainda é pendência (A01 do [backlog da fase 3](fase-3-backlog.md)).
- OIDC e Vault são falsos; os dados somem ao parar; não há observabilidade nem a fila de exportações persistente.
- Serve para validar fluxos e a interface, não para medir desempenho nem para expor a outras pessoas na rede.
