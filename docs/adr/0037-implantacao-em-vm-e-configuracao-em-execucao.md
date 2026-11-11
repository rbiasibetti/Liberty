# 0037. Implantação em VM e configuração em tempo de execução

- Status: aceito
- Data: 2026-11-10
- Etapa: A01/A02 da fase 3

## Contexto

O plano assumia contêineres e Kubernetes, e o orquestrador era decisão em aberto. Não há servidor em nuvem para o piloto, e nenhuma imagem foi construída ou aplicada até aqui. Dois pontos pesavam: o jar executável do Spring Boot não serve de `-cp` para o processo trabalhador de script (ADR 0026), e o frontend embutia no build o endereço do provedor de login, o que obrigava um build por ambiente.

## Decisão

- **Construir uma vez, configurar no deploy.** O pacote (`deploy/vm/package.sh`) leva o jar, a pasta `worker-lib` e a interface compilada, sem nenhum valor de ambiente. O mesmo pacote vai para HML e PRD.
- **Trabalhador de script:** o módulo `liberty-scripts` gera `target/worker-lib` (o próprio jar mais GraalJS, Jackson e SLF4J, sem Spring). A API aponta `liberty.scripts.worker-classpath` (`LIBERTY_SCRIPTS_WORKER_CLASSPATH`) para `worker-lib/*`. Vale para a VM, para a imagem (`backend/Dockerfile`) e para qualquer `java -jar`.
- **Configuração da interface em tempo de execução:** a interface lê `/config.json` ao iniciar (`loadRuntimeConfig`) e esse arquivo vence os valores embutidos no build, campo a campo; sem ele, valem os `VITE_*`. O `install.sh` o grava em `/etc/liberty/config.json`, fora do pacote. Não contém segredos (só endereços e o identificador do cliente público).
- **Caminho de implantação do piloto:** VM com systemd e nginx (`deploy/vm`): `install.sh` idempotente que nunca sobrescreve `/etc/liberty`, pacote por versão em `/opt/liberty/releases` com `current` e `previous`, `rollback.sh`, três versões guardadas, serviço endurecido (sem privilégios novos, sistema de arquivos somente leitura, usuário próprio), API só em `127.0.0.1`, nginx com a CSP incluindo a origem do provedor de login.
- **Kubernetes continua possível** com o mesmo jar e o mesmo `worker-lib` (a imagem os empacota); não foi descartado, só não é o caminho do piloto.

## Consequências

- O A01 está resolvido no jar: `PackagedWorkerTest` (só a pasta `worker-lib` roda scripts com toda a API do anfitrião) e `scripts/smoke-jar.sh` (o jar de verdade executa `beforeExecute` e `afterExecute` no sandbox). Sem a pasta, o jar responde 429 ("scripts demais") e o log aponta o classpath.
- `package.sh` foi executado (gera o pacote com jar, `worker-lib` e a interface). `install.sh` e `rollback.sh` foram escritos e checados sintaticamente, mas **não executados numa VM**: exigem root, systemd e nginx. A primeira instalação real deve ser em homologação.
- Sem TLS configurado, o login e os tokens trafegam em claro: o modelo do nginx deixa a instrução, mas o certificado é responsabilidade de quem implanta.
- Várias instâncias exigem exportações em volume compartilhado (ou o adaptador S3, C01) e Redis para o limite de taxa somado.
