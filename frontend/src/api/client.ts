import createClient, { type Middleware } from 'openapi-fetch';
import { toApiError } from './problem';
import type { paths } from './schema';

export interface ClientOptions {
  baseUrl: string;
  /** Token de acesso atual, ou nulo se não há sessão. */
  getToken: () => string | null;
  /** Chamado quando a API responde 401: a sessão acabou. */
  onUnauthorized: () => void;
}

/** Cliente tipado da API de administração: envia o token e converte erros em ApiError. */
export function createApiClient(options: ClientOptions) {
  const middleware: Middleware = {
    onRequest({ request }) {
      const token = options.getToken();
      if (token) {
        request.headers.set('Authorization', `Bearer ${token}`);
      }
      return request;
    },
    async onResponse({ response }) {
      if (response.ok) {
        return undefined;
      }
      if (response.status === 401) {
        options.onUnauthorized();
      }
      const body: unknown = await response
        .clone()
        .json()
        .catch(() => undefined);
      throw toApiError(response, body);
    },
  };
  const client = createClient<paths>({ baseUrl: options.baseUrl });
  client.use(middleware);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
