# 0035. Filas por tenant

- Status: aceito
- Data: 2026-11-07
- Etapa: E23

## Contexto

A fase 2 pede filas com envelope de tenant, fila de mensagens mortas (DLQ), consumidores com bulkhead e, "se decidida", entrega garantida da auditoria. O critério de saída é que a falha de um tenant não bloqueie a fila. Não há broker (Kafka, RabbitMQ) na infraestrutura prevista, e o volume esperado é de eventos de integração por tenant, não de um fluxo de milhões de mensagens.

## Decisão

**Fila durável no banco central** (`int_queue_message`, V5), sem broker novo. Vantagens: nada a operar além do SQL Server que já existe, backup e restauração do core cobrem as mensagens, e a administração é uma consulta. O custo é o teto de vazão de um banco relacional; se um dia a fila passar de milhares de mensagens por segundo, troca-se o armazenamento atrás de `QueueRepository`, sem mudar scripts, consumidores nem API.

**Envelope.** A mensagem leva o tenant e o ambiente; o consumidor roda com o contexto de tenant da própria mensagem, nunca com o de quem o chamou. `QueueEnvelope`: id, tenant, ambiente, fila, tentativa, correlação, criação, conteúdo.

**Entrega pelo menos uma vez.** Reivindicação por `UPDATE TOP (n) ... WITH (ROWLOCK, UPDLOCK, READPAST) ... OUTPUT`: dois consumidores nunca pegam a mesma linha e nenhum bloqueia o outro. A mensagem fica `PROCESSING` com um arrendamento (60 s); se o consumidor morre, o arrendamento vence e ela volta (ou morre, se as tentativas acabaram). Consumidores precisam ser idempotentes; o webhook leva `X-Liberty-Message-Id` estável nas reentregas.

**Recuo e DLQ.** Falha comum agenda nova tentativa em 2 s, 8 s, 32 s, 128 s (×4, teto de 10 min); na 5ª, a mensagem vira `DEAD`. `QueueHandler.Permanent` (conteúdo malformado, destino recusou, política violada) mata de uma vez. Mortas ficam 30 dias; PUBLISHER vê (sem o conteúdo), devolve à fila ou descarta. Alerta `LibertyFilaMensagemMorta`.

**Isolamento entre tenants (o critério).**
- Teto de mensagens em processamento **por tenant** (2), estritamente menor que o total da instância (16): a configuração que viole isso não sobe.
- A vaga do tenant é adquirida **antes** de reivindicar; um tenant sem vaga não tem mensagens tiradas da fila.
- A rodada começa pelo tenant com a mensagem mais antiga, então ninguém passa fome.
- Cada mensagem é independente (sem ordenação global ou por fila): uma venenosa nunca segura as seguintes.
- Fila de cada tenant limitada (10 mil pendentes); passou disso, `queue.publish` recusa.
- Teste (`QueueTest`): um tenant com 6 mensagens travadas ocupa só 2 vagas; o outro processa as suas 6 sem esperar.

**Quem publica.** Scripts, com `queue.publish(nome, objeto)`, **somente em `afterExecute`**, e a mensagem só é gravada depois do commit da escrita no banco do tenant: durante o script ela fica numa lista da thread, descartada se a transação desfaz ou repete por impasse. No sandbox nada é publicado (valida e devolve `simulated: true`). Uma fila tem um consumidor registrado (`QueueHandler`); hoje existe um, `webhook`.

**Fila `webhook`.** Entrega HTTPS pelo mesmo `http.fetch` da E22 (lista de hosts do tenant, só endereços públicos, sem redirecionamento): 2xx conclui; 408/425/429/5xx e falhas de rede tentam de novo; qualquer outra resposta e toda recusa de política vão direto para as mortas.

**Entrega garantida da auditoria: decidido não adotar agora.** A auditoria segue na fila limitada em memória com descarte contado (ADR 0029), alerta em qualquer descarte e nenhuma falha de requisição por causa dela. Trocar por esta fila custaria uma escrita síncrona no central por requisição, derrubando o orçamento de latência, e nenhum cliente exigiu auditoria sem perda. Reabrir se um contrato exigir; o caminho é gravar o evento no central dentro de um `INSERT` leve no mesmo caminho da resposta, não esta fila.

## Consequências

- **Não é atômico com o banco do tenant.** Não há como unir a transação do banco do cliente com a do central. Uma queda entre o commit e a gravação perde a mensagem (no máximo uma vez na publicação); falhas de gravação são contadas (`liberty.queue.publish.failed`, alerta crítico) e registradas. Se um caso exigir publicação garantida, o padrão é o integrador gravar uma linha de "evento pendente" no próprio banco e um consumidor de leitura publicar.
- A administração mostra estado, nunca o conteúdo, porque ele pode carregar dados do cliente.
- Falta uma tela para filas e mortas: só API. Não há consumidor além do webhook.
- Sem ordenação: dois eventos da mesma entidade podem chegar fora de ordem; o destino deve usar o conteúdo, não a ordem de chegada.
