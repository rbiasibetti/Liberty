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
