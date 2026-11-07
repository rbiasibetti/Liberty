import { describe, expect, it } from 'vitest';
import { ApiError, describeError, toApiError } from './problem';

function response(status: number, headers: Record<string, string> = {}) {
  return new Response(null, { status, headers });
}

describe('toApiError', () => {
  it('lê o problema da API com requestId e violações por campo', () => {
    const error = toApiError(response(400, { 'Retry-After': '7' }), {
      type: 'urn:liberty:problem:validation',
      detail: 'Um ou mais parâmetros são inválidos',
      requestId: 'abc-123',
      errors: [{ parameter: '/numero', message: 'Valor obrigatório' }],
    });

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.type).toBe('urn:liberty:problem:validation');
    expect(error.requestId).toBe('abc-123');
    expect(error.violations).toHaveLength(1);
    expect(error.retryAfterSeconds).toBe(7);
  });

  it('usa mensagem padrão por status e o X-Request-Id quando não há corpo', () => {
    const error = toApiError(response(403, { 'X-Request-Id': 'req-9' }), undefined);

    expect(error.message).toContain('permissão');
    expect(error.requestId).toBe('req-9');
    expect(error.type).toBe('about:blank');
    expect(error.retryAfterSeconds).toBeUndefined();
    expect(toApiError(response(418), 'texto').message).toBe('Erro 418 na API.');
  });
});

describe('describeError', () => {
  it('junta as primeiras violações na mensagem', () => {
    const error = new ApiError({
      status: 400,
      type: 't',
      message: 'Inválido',
      requestId: undefined,
      retryAfterSeconds: undefined,
      violations: [{ parameter: '/a', message: 'obrigatório' }, { message: 'sem campo' }, {}],
    });

    expect(describeError(error)).toBe('Inválido (/a: obrigatório; sem campo)');
  });

  it('trata falha de rede e erros desconhecidos sem vazar detalhes', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toContain('conexão');
    expect(describeError(new Error('segredo interno'))).toBe('Algo deu errado. Tente novamente.');
    expect(describeError('x')).toBe('Algo deu errado. Tente novamente.');
  });
});
