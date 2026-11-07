import { useCallback, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { ApiClient } from '../api/client';
import { useRequest } from '../api/useRequest';
import { TenantContext, hasRole } from './tenantContext';
import type { Role, TenantContextValue } from './tenantContext';

const STORAGE_KEY = 'liberty.tenant';

/** Carrega os tenants do usuário (`/api/v1/me`) e guarda o escolhido na aba. */
export function TenantProvider({ api, children }: { api: ApiClient; children: ReactNode }) {
  const [selected, setSelected] = useState<string | null>(() =>
    window.sessionStorage.getItem(STORAGE_KEY),
  );
  const fetchMe = useCallback(async () => (await api.GET('/api/v1/me')).data, [api]);
  const me = useRequest('me', fetchMe);

  const memberships = useMemo(() => (me.data?.tenants ?? {}) as Record<string, Role[]>, [me.data]);
  const slugs = useMemo(() => Object.keys(memberships).sort(), [memberships]);
  const current =
    selected && slugs.includes(selected)
      ? selected
      : me.status === 'ready'
        ? (slugs[0] ?? null)
        : null;

  const select = useCallback((slug: string) => {
    window.sessionStorage.setItem(STORAGE_KEY, slug);
    setSelected(slug);
  }, []);

  const value = useMemo<TenantContextValue>(
    () => ({
      state: me.status,
      error: me.error ?? null,
      reload: me.reload,
      platformAdmin: me.data?.platformAdmin ?? false,
      tenants: slugs,
      current,
      select,
      can: (minimum: Role) => current !== null && hasRole(memberships[current] ?? [], minimum),
    }),
    [me.status, me.error, me.reload, me.data, slugs, current, select, memberships],
  );
  return <TenantContext.Provider value={value}>{children}</TenantContext.Provider>;
}
