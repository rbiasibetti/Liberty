import type { User, UserManager } from 'oidc-client-ts';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { AuthContext, rememberReturnTo } from './authContext';
import type { AuthStatus } from './authContext';

export function AuthProvider({
  manager,
  children,
}: {
  manager: Pick<
    UserManager,
    'getUser' | 'signinRedirect' | 'signoutRedirect' | 'removeUser' | 'events'
  >;
  children: ReactNode;
}) {
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');

  useEffect(() => {
    let active = true;
    const apply = (next: User | null | undefined) => {
      if (!active) {
        return;
      }
      const valid = next && !next.expired ? next : null;
      setUser(valid);
      setStatus(valid ? 'authenticated' : 'anonymous');
    };
    manager.getUser().then(apply, () => {
      apply(null);
    });
    const loaded = (next: User) => {
      apply(next);
    };
    const gone = () => {
      apply(null);
    };
    manager.events.addUserLoaded(loaded);
    manager.events.addUserUnloaded(gone);
    manager.events.addAccessTokenExpired(gone);
    manager.events.addSilentRenewError(gone);
    return () => {
      active = false;
      manager.events.removeUserLoaded(loaded);
      manager.events.removeUserUnloaded(gone);
      manager.events.removeAccessTokenExpired(gone);
      manager.events.removeSilentRenewError(gone);
    };
  }, [manager]);

  const login = useCallback(
    async (returnTo?: string) => {
      rememberReturnTo(returnTo);
      await manager.signinRedirect();
    },
    [manager],
  );

  const logout = useCallback(async () => {
    window.sessionStorage.clear();
    await manager.signoutRedirect();
  }, [manager]);

  const token = useCallback(() => (user && !user.expired ? user.access_token : null), [user]);

  const value = useMemo(
    () => ({ status, user, token, login, logout }),
    [status, user, token, login, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
