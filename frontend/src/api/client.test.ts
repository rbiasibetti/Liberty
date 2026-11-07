import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApiClient } from './client';
import { ApiError } from './problem';
import { json } from '../test/helpers';

function clientWith(fetchImpl: typeof fetch, token: string | null, onUnauthorized = vi.fn()) {
  vi.stubGlobal('fetch', fetchImpl);
  return {
    onUnauthorized,
    api: createApiClient({ baseUrl: 'http://api.test', getToken: () => token, onUnauthorized }),
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('createApiClient', () => {
  it('envia o token em cada chamada e devolve o corpo', async () => {
    const seen: string[] = [];
    const { api } = clientWith(
      vi.fn((input: Request | URL | string) => {
        seen.push((input as Request).headers.get('Authorization') ?? '');
        return Promise.resolve(json({ sub: 'ana', platformAdmin: false, tenants: {} }));
      }),
      'abc',
    );

    const { data } = await api.GET('/api/v1/me');

    expect(seen).toEqual(['Bearer abc']);
    expect(data?.sub).toBe('ana');
  });

  it('não envia Authorization sem sessão', async () => {
    const seen: (string | null)[] = [];
    const { api } = clientWith(
      vi.fn((input: Request | URL | string) => {
        seen.push((input as Request).headers.get('Authorization'));
        return Promise.resolve(json({ sub: 'x', platformAdmin: false, tenants: {} }));
      }),
      null,
    );

    await api.GET('/api/v1/me');

    expect(seen).toEqual([null]);
  });

  it('converte problem+json em ApiError e avisa quando a sessão acabou', async () => {
    const { api, onUnauthorized } = clientWith(
      vi.fn(() =>
        Promise.resolve(
          json(
            { type: 'urn:liberty:problem:unauthorized', detail: 'Token inválido' },
            { status: 401 },
          ),
        ),
      ),
      'velho',
    );

    const failure = await api.GET('/api/v1/me').catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(401);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('trata corpo de erro que não é JSON', async () => {
    const { api, onUnauthorized } = clientWith(
      vi.fn(() => Promise.resolve(new Response('<html>', { status: 502 }))),
      'abc',
    );

    const failure = await api.GET('/api/v1/me').catch((e: unknown) => e);

    expect((failure as ApiError).status).toBe(502);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
