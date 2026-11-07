/** Defeito localizado num campo ou parâmetro (item de `errors[]` do problema). */
export interface Violation {
  parameter?: string;
  code?: string;
  message?: string;
}

/** Erro da API (RFC 9457) já em forma segura para exibir. */
export class ApiError extends Error {
  readonly status: number;
  readonly type: string;
  readonly requestId: string | undefined;
  readonly violations: Violation[];
  readonly retryAfterSeconds: number | undefined;

  constructor(init: {
    status: number;
    type: string;
    message: string;
    requestId: string | undefined;
    violations?: Violation[];
    retryAfterSeconds: number | undefined;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.type = init.type;
    this.requestId = init.requestId;
    this.violations = init.violations ?? [];
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}

const GENERIC: Record<number, string> = {
  401: 'Sua sessão expirou. Entre novamente.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontrado.',
  429: 'Muitas requisições. Tente de novo em instantes.',
  503: 'Serviço indisponível. Tente de novo em instantes.',
  504: 'A operação demorou demais. Tente de novo.',
};

/** Converte a resposta de erro (problem+json ou outra) em {@link ApiError}. */
export function toApiError(response: Response, body: unknown): ApiError {
  const problem =
    typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  const detail = typeof problem.detail === 'string' ? problem.detail : undefined;
  const retry = Number(response.headers.get('Retry-After'));
  return new ApiError({
    status: response.status,
    type: typeof problem.type === 'string' ? problem.type : 'about:blank',
    message: detail ?? GENERIC[response.status] ?? `Erro ${String(response.status)} na API.`,
    requestId:
      typeof problem.requestId === 'string'
        ? problem.requestId
        : (response.headers.get('X-Request-Id') ?? undefined),
    violations: Array.isArray(problem.errors) ? (problem.errors as Violation[]) : [],
    retryAfterSeconds: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  });
}

/** Mensagem curta para o usuário de qualquer erro capturado. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    const where = error.violations
      .slice(0, 3)
      .map((v) => [v.parameter, v.message].filter(Boolean).join(': '))
      .filter(Boolean);
    return where.length > 0 ? `${error.message} (${where.join('; ')})` : error.message;
  }
  if (error instanceof TypeError) {
    return 'Não foi possível falar com o servidor. Verifique sua conexão.';
  }
  return 'Algo deu errado. Tente novamente.';
}
