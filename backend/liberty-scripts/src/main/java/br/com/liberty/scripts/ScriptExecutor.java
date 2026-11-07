package br.com.liberty.scripts;

import java.time.Duration;

/** Executa scripts de integradores fora do processo da API (ADR 0017). */
public interface ScriptExecutor {

  /** Como uma execução terminou; uma execução nunca lança exceção por culpa do script. */
  enum Status {
    OK,
    /** Passou do prazo ou do limite de instruções. */
    TIMEOUT,
    /** Erro de execução do script (inclui acesso negado). */
    SCRIPT_ERROR,
    /** O script pediu a recusa da operação ({@code fail}). */
    BUSINESS_FAILURE,
    /** O trabalhador morreu (ex.: memória esgotada) ou ficou indisponível. */
    WORKER_FAILURE
  }

  /** {@code function} é chamada com {@code inputJson} já convertido; devolve JSON. */
  record Request(
      String script, String function, String inputJson, Duration timeout, long maxStatements) {}

  /**
   * Resultado: {@code json} só no OK; {@code message} é curta e vem do script ou do motor; em
   * BUSINESS_FAILURE, {@code httpStatus} é o pedido pelo script.
   */
  record Outcome(
      Status status, String json, String message, int httpStatus, String logs, long millis) {}

  /** Chamadas que o script faz ao pai (única forma de tocar dados). */
  interface HostCalls {
    /** Executa a consulta nomeada e devolve um array JSON de linhas. */
    String named(String name, String paramsJson);

    /** Valor de uma coluna da linha que tem a chave dada; JSON escalar ou {@code null}. */
    String lookup(String table, String keyColumn, String keyJson, String valueColumn);

    /**
     * Chamada HTTP de saída ({@code http.fetch}); {@code bodyJson} é um JSON string ou {@code
     * null}. Devolve {@code {status, ok, headers, body}} em JSON; recusas lançam exceção de
     * mensagem segura.
     */
    String fetch(String method, String url, String headersJson, String bodyJson);
  }

  /** O pool está cheio e a espera por um trabalhador acabou. */
  final class BusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BusyException() {
      super("Nenhum trabalhador de script disponível");
    }
  }

  Outcome run(Request request, HostCalls host);
}
