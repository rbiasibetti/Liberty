import { MantineProvider } from '@mantine/core';
import type { User } from 'oidc-client-ts';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';

/** Gerenciador OIDC falso: o que a aplicação usa do UserManager, sem rede. */
export function fakeManager(user: Partial<User> | null) {
  const listeners = new Map<string, ((...args: never[]) => void)[]>();
  const add = (name: string) => (cb: (...args: never[]) => void) => {
    listeners.set(name, [...(listeners.get(name) ?? []), cb]);
  };
  const remove = (name: string) => (cb: (...args: never[]) => void) => {
    listeners.set(
      name,
      (listeners.get(name) ?? []).filter((x) => x !== cb),
    );
  };
  return {
    getUser: vi.fn(() => Promise.resolve(user as User | null)),
    signinRedirect: vi.fn(() => Promise.resolve()),
    signinRedirectCallback: vi.fn(() => Promise.resolve({} as User)),
    signoutRedirect: vi.fn(() => Promise.resolve()),
    removeUser: vi.fn(() => Promise.resolve()),
    events: {
      addUserLoaded: add('loaded'),
      removeUserLoaded: remove('loaded'),
      addUserUnloaded: add('unloaded'),
      removeUserUnloaded: remove('unloaded'),
      addAccessTokenExpired: add('expired'),
      removeAccessTokenExpired: remove('expired'),
      addSilentRenewError: add('renewError'),
      removeSilentRenewError: remove('renewError'),
    },
    emit(name: string, ...args: unknown[]) {
      (listeners.get(name) ?? []).forEach((cb) => {
        (cb as (...a: unknown[]) => void)(...args);
      });
    },
  };
}

export function validUser(overrides: Partial<User> = {}): Partial<User> {
  return {
    access_token: 'token-de-teste',
    expired: false,
    profile: { sub: 'ana', preferred_username: 'ana' } as User['profile'],
    ...overrides,
  };
}

export function Wrapper({ children, path = '/' }: { children: ReactNode; path?: string }) {
  return (
    <MantineProvider>
      <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
    </MantineProvider>
  );
}

/** Resposta JSON para o `fetch` falso. */
export function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: {
      'Content-Type':
        init.status && init.status >= 400 ? 'application/problem+json' : 'application/json',
      ...(init.headers as Record<string, string> | undefined),
    },
    ...init,
  });
}

/** Primeiro elemento de uma lista de resultados de busca (falha se vier vazia). */
export function first<T>(items: T[]): T {
  return nth(items, 0);
}

/** Elemento de posição `index` de uma lista de resultados (falha se não existir). */
export function nth<T>(items: T[], index: number): T {
  const item = items[index];
  if (item === undefined) {
    throw new Error('Nenhum elemento encontrado');
  }
  return item;
}
