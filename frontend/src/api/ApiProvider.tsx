import { useLayoutEffect, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useAuth } from '../auth/useAuth';
import { ApiContext } from './apiContext';
import { createApiClient } from './client';
import { sessionToken, sessionUnauthorized, setSessionBridge } from './session';

/** Cliente da API ligado à sessão: o token é lido a cada chamada e 401 leva ao login. */
export function ApiProvider({ baseUrl, children }: { baseUrl: string; children: ReactNode }) {
  const auth = useAuth();
  // Efeito de layout: roda antes dos efeitos dos filhos, que fazem as primeiras chamadas já com o
  // token atual (com useEffect, a primeira chamada após o login ia sem token e voltava ao login).
  useLayoutEffect(() => {
    setSessionBridge({
      token: auth.token,
      onUnauthorized: () => {
        void auth.login(window.location.pathname);
      },
    });
  }, [auth]);
  const client = useMemo(
    () =>
      createApiClient({
        baseUrl,
        getToken: sessionToken,
        onUnauthorized: sessionUnauthorized,
      }),
    [baseUrl],
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}
