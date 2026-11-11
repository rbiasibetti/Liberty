/** Configuração de execução, vinda das variáveis VITE_* (ver .env.example). */
export interface AppConfig {
  /** Origem da API; vazio usa a mesma origem (em desenvolvimento, o proxy do Vite). */
  apiUrl: string;
  oidcAuthority: string;
  oidcClientId: string;
}

export function loadConfig(env: Record<string, string | undefined> = import.meta.env): AppConfig {
  return {
    apiUrl: (env.VITE_API_URL ?? '').replace(/\/+$/, ''),
    oidcAuthority: env.VITE_OIDC_AUTHORITY ?? 'http://127.0.0.1:8081/realms/liberty',
    oidcClientId: env.VITE_OIDC_CLIENT_ID ?? 'liberty-frontend',
  };
}

type Fetcher = (url: string, init?: RequestInit) => Promise<Pick<Response, 'ok' | 'json'>>;

/**
 * Configuração de execução: o que o deploy publica em {@code /config.json} vence o que foi embutido
 * no build (VITE_*). Assim o mesmo build serve a qualquer ambiente, e o endereço da API e do
 * provedor de login se definem na implantação. Sem o arquivo (ou com algo que não é JSON, como a
 * página de entrada devolvida pelo servidor de desenvolvimento), vale o build.
 */
export async function loadRuntimeConfig(
  fetcher: Fetcher = (url, init) => fetch(url, init),
  env: Record<string, string | undefined> = import.meta.env,
): Promise<AppConfig> {
  const base = loadConfig(env);
  try {
    const response = await fetcher('/config.json', { cache: 'no-store' });
    if (!response.ok) {
      return base;
    }
    const given: unknown = await response.json();
    if (typeof given !== 'object' || given === null) {
      return base;
    }
    const text = (key: string): string | undefined => {
      const value = (given as Record<string, unknown>)[key];
      return typeof value === 'string' && value !== '' ? value : undefined;
    };
    return {
      apiUrl: (text('apiUrl') ?? base.apiUrl).replace(/\/+$/, ''),
      oidcAuthority: text('oidcAuthority') ?? base.oidcAuthority,
      oidcClientId: text('oidcClientId') ?? base.oidcClientId,
    };
  } catch {
    return base;
  }
}
