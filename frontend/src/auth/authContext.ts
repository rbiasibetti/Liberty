import type { User } from 'oidc-client-ts';
import { createContext } from 'react';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthContextValue {
  status: AuthStatus;
  user: User | null;
  /** Token de acesso atual (ou nulo). */
  token: () => string | null;
  login: (returnTo?: string) => Promise<void>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

/** Para onde voltar depois do login (guardado só na aba, nunca dado do usuário). */
export const RETURN_KEY = 'liberty.returnTo';

function safePath(path: string | null | undefined): string | null {
  return path?.startsWith('/') && !path.startsWith('//') ? path : null;
}

export function rememberReturnTo(path: string | undefined) {
  const safe = safePath(path);
  if (safe) {
    window.sessionStorage.setItem(RETURN_KEY, safe);
  }
}

/** Destino guardado antes do login, consumido uma vez. */
export function takeReturnTo(): string {
  const stored = window.sessionStorage.getItem(RETURN_KEY);
  window.sessionStorage.removeItem(RETURN_KEY);
  return safePath(stored) ?? '/';
}
