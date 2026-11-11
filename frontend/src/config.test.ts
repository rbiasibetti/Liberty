import { describe, expect, it } from 'vitest';
import { loadRuntimeConfig } from './config';

const env = {
  VITE_API_URL: 'https://build.test',
  VITE_OIDC_AUTHORITY: 'https://idp-build.test/realms/x',
  VITE_OIDC_CLIENT_ID: 'build-client',
};

function serving(body: unknown, ok = true) {
  return () => Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

describe('loadRuntimeConfig', () => {
  it('o config.json do deploy vence o que veio embutido no build, campo a campo', async () => {
    const config = await loadRuntimeConfig(
      serving({
        oidcAuthority: 'https://idp-prod.test/realms/liberty',
        apiUrl: 'https://api.test//',
      }),
      env,
    );

    expect(config).toEqual({
      apiUrl: 'https://api.test',
      oidcAuthority: 'https://idp-prod.test/realms/liberty',
      oidcClientId: 'build-client',
    });
  });

  it('sem arquivo útil vale o build: ausente, não-JSON, valores inválidos ou rede fora', async () => {
    const fromBuild = {
      apiUrl: 'https://build.test',
      oidcAuthority: 'https://idp-build.test/realms/x',
      oidcClientId: 'build-client',
    };

    expect(await loadRuntimeConfig(serving({}, false), env)).toEqual(fromBuild);
    expect(await loadRuntimeConfig(serving(null), env)).toEqual(fromBuild);
    expect(await loadRuntimeConfig(serving('texto'), env)).toEqual(fromBuild);
    expect(await loadRuntimeConfig(serving({ apiUrl: 5, oidcClientId: '' }), env)).toEqual(
      fromBuild,
    );
    expect(await loadRuntimeConfig(() => Promise.reject(new Error('rede')), env)).toEqual(
      fromBuild,
    );
    expect(
      await loadRuntimeConfig(
        () =>
          Promise.resolve({
            ok: true,
            json: () => Promise.reject(new SyntaxError('<!doctype html>')),
          }),
        env,
      ),
    ).toEqual(fromBuild);
  });
});
