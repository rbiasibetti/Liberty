package br.com.liberty.engine.script;

import br.com.liberty.domain.tenancy.TenantScope;
import br.com.liberty.engine.route.PublishedIntegration;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Ganchos de script da integração. {@code beforeExecute} (só em escrita) pode trocar o corpo antes
 * do mapeamento; {@code afterExecute} pode trocar o corpo da resposta. Sem script declarado,
 * devolve o valor recebido.
 */
public interface ScriptHooks {

  /** Nenhum script: tudo passa direto. */
  ScriptHooks NONE =
      new ScriptHooks() {
        @Override
        public JsonNode before(
            TenantScope scope,
            PublishedIntegration integration,
            Map<String, String> params,
            JsonNode body) {
          return body;
        }

        @Override
        public JsonNode after(
            TenantScope scope,
            PublishedIntegration integration,
            Map<String, String> params,
            JsonNode body,
            JsonNode result) {
          return result;
        }
      };

  /** Corpo (possivelmente substituído) que segue para o mapeamento. */
  JsonNode before(
      TenantScope scope,
      PublishedIntegration integration,
      Map<String, String> params,
      JsonNode body);

  /** Corpo de resposta (possivelmente substituído); {@code body} é nulo em leituras. */
  JsonNode after(
      TenantScope scope,
      PublishedIntegration integration,
      Map<String, String> params,
      JsonNode body,
      JsonNode result);
}
