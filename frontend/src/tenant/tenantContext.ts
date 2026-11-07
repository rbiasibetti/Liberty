import { createContext, useContext } from 'react';

export type Role = 'VIEWER' | 'INTEGRATOR' | 'PUBLISHER';

const ORDER: Role[] = ['VIEWER', 'INTEGRATOR', 'PUBLISHER'];

/** Hierarquia do backend: PUBLISHER inclui INTEGRATOR, que inclui VIEWER. */
export function hasRole(roles: Role[], minimum: Role): boolean {
  const need = ORDER.indexOf(minimum);
  return roles.some((role) => ORDER.indexOf(role) >= need);
}

export interface TenantContextValue {
  state: 'loading' | 'ready' | 'error';
  error: string | null;
  reload: () => void;
  platformAdmin: boolean;
  tenants: string[];
  current: string | null;
  select: (slug: string) => void;
  /** O usuário tem pelo menos este papel no tenant atual? */
  can: (minimum: Role) => boolean;
}

export const TenantContext = createContext<TenantContextValue | null>(null);

export function useTenant(): TenantContextValue {
  const value = useContext(TenantContext);
  if (!value) {
    throw new Error('useTenant fora do TenantProvider');
  }
  return value;
}
