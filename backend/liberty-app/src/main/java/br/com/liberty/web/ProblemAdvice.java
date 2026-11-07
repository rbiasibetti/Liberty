package br.com.liberty.web;

import br.com.liberty.engine.problem.ProblemException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Toda falha da API de execução sai como problem+json (RFC 9457) com o requestId; nada de stack
 * trace.
 */
@RestControllerAdvice
class ProblemAdvice {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

  @ExceptionHandler(ProblemException.class)
  ResponseEntity<ProblemDetail> problem(ProblemException e) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
    body.setType(URI.create(e.type()));
    body.setTitle(e.title());
    describe(body);
    if (!e.violations().isEmpty()) {
      List<Map<String, String>> errors =
          e.violations().stream()
              .map(
                  v -> Map.of("parameter", v.parameter(), "code", v.code(), "message", v.message()))
              .toList();
      body.setProperty("errors", errors);
    }
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    e.headers().forEach(response::header);
    return response.body(body);
  }

  /**
   * O firewall recusou algo na requisição (ex.: nome de parâmetro com caracteres proibidos): é
   * entrada inválida.
   */
  @ExceptionHandler(RequestRejectedException.class)
  ResponseEntity<ProblemDetail> rejected(RequestRejectedException e) {
    LOG.warn("Requisição rejeitada pelo firewall: {}", e.getMessage());
    return problem(
        new ProblemException(
            400,
            "invalid-request",
            "Requisição inválida",
            "A requisição contém caracteres não permitidos"));
  }

  /** `instance` identifica esta ocorrência (o requestId), nunca o caminho recebido. */
  private static void describe(ProblemDetail body) {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    body.setProperty("requestId", requestId);
    if (requestId != null) {
      body.setInstance(URI.create("urn:liberty:request:" + requestId));
    }
  }

  /** Falha inesperada: resposta genérica; o detalhe só vai para o log, com o mesmo requestId. */
  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<ProblemDetail> unexpected(RuntimeException e) {
    if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
      throw e; // quem trata 401/403 é a cadeia de segurança
    }
    LOG.error("Falha inesperada", e);
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
    body.setType(URI.create("urn:liberty:problem:internal"));
    body.setTitle("Erro interno");
    describe(body);
    return ResponseEntity.status(500).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
