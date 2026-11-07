/**
 * Ponte entre o cliente da API e a sessão de login: o cliente é criado uma vez e lê o token e a
 * reação a 401 daqui, que o ApiProvider mantém atualizados.
 */
interface SessionBridge {
  token: () => string | null;
  onUnauthorized: () => void;
}

let bridge: SessionBridge = { token: () => null, onUnauthorized: () => undefined };

export function setSessionBridge(next: SessionBridge) {
  bridge = next;
}

export function sessionToken(): string | null {
  return bridge.token();
}

export function sessionUnauthorized() {
  bridge.onUnauthorized();
}
