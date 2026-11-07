import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { AppConfig } from '../config';

/**
 * Login OIDC por código de autorização com PKCE (S256, o padrão da biblioteca para clientes
 * públicos). A sessão fica no sessionStorage (some ao fechar a aba) e é renovada com refresh token.
 */
export function createUserManager(config: AppConfig, origin: string = window.location.origin) {
  return new UserManager({
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: `${origin}/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
}
