# Implantação em VM (systemd + nginx)

Caminho mais simples para um piloto ou para um ambiente pequeno: sem Docker nem Kubernetes. O pacote é **construído uma vez** e a **configuração é só do ambiente**.

| O quê | Onde fica |
| --- | --- |
| Pacote (jar, `worker-lib`, interface compilada) | `/opt/liberty/releases/<versão>/`, com `current` apontando para a ativa e `previous` para a anterior |
| Variáveis da API e segredos | `/etc/liberty/backend.env` (root, modo 0640, grupo `liberty`) |
| Endereço da API e do login para a interface | `/etc/liberty/config.json` (lido pelo navegador; sem segredos) |
| Exportações geradas | `/var/lib/liberty/exports` |
| Serviço | `liberty-backend` (systemd, usuário `liberty`, endurecido) |
| Proxy e arquivos estáticos | nginx, `/etc/nginx/conf.d/liberty.conf` |

## Pré-requisitos na VM

Java 25 (JRE), nginx, curl, python3 e um usuário com `sudo`. Fora da VM: o banco central `liberty_core` migrado (`liberty-core-migrations`), um Vault, um provedor OIDC (Keycloak ou Entra ID) com o cliente público `liberty-frontend` e um certificado TLS (por exemplo com certbot): **sem TLS não vá para produção**.

## 1. Gerar o pacote (máquina de build)

```bash
deploy/vm/package.sh 0.1.0          # Java 25 e Node 22+; gera deploy/vm/liberty-0.1.0.tar.gz e o .sha256
```

## 2. Instalar (na VM)

```bash
scp deploy/vm/liberty-0.1.0.tar.gz* usuario@vm:/tmp/
ssh usuario@vm
cd /tmp && sha256sum -c liberty-0.1.0.tar.gz.sha256 && tar xzf liberty-0.1.0.tar.gz && cd liberty-0.1.0
sudo ./install.sh --server-name liberty.exemplo.com \
     --oidc-authority https://login.exemplo.com/realms/liberty
```

Na primeira vez o script cria `/etc/liberty/backend.env` a partir do modelo e **não sobe o serviço**: preencha os valores e rode `sudo systemctl restart liberty-backend`. Nas próximas, ele só troca a versão e reinicia, sem tocar na configuração.

## 3. Conferir

```bash
curl -s http://127.0.0.1:8080/actuator/health/readiness     # na VM
curl -s https://liberty.exemplo.com/healthz                  # de fora
journalctl -u liberty-backend -n 100 --no-pager
```

## Atualizar e voltar

```bash
# nova versão: gere outro pacote (package.sh 0.1.1), copie e rode o install.sh de novo
sudo ./install.sh --server-name liberty.exemplo.com --oidc-authority https://login.exemplo.com/realms/liberty
sudo ./rollback.sh          # de dentro de qualquer pacote extraído
```

O `rollback.sh` troca `current` por `previous` e reinicia em segundos. As 3 versões mais recentes ficam guardadas. Migrações do banco central não voltam sozinhas: elas são compatíveis com a versão anterior ou o rollback precisa de restauração (ver `docs/runbooks/restaurar-core.md`).

## Notas

- A API escuta só em `127.0.0.1:8080`; quem atende o público é o nginx.
- Mais de uma instância da API exige o armazenamento de exportações compartilhado (ou o adaptador S3, item C01 do backlog) e `LIBERTY_REDIS_URI` para o limite de taxa somado.
- Memória: a API mais `poolSize × (heap + 100 MB)` dos processos de script (padrão 2 × 228 MB).
- Este caminho **não foi executado numa VM real**: `package.sh` e `install.sh` foram escritos e checados sintaticamente. A primeira instalação de verdade deve ser em homologação.
