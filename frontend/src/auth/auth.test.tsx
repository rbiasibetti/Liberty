import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { loadConfig } from '../config';
import { fakeManager, validUser, Wrapper } from '../test/helpers';
import { AuthProvider } from './AuthProvider';
import { rememberReturnTo, takeReturnTo } from './authContext';
import { CallbackPage } from './CallbackPage';
import { createUserManager } from './userManager';
import { useAuth } from './useAuth';

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <span>status:{auth.status}</span>
      <span>token:{auth.token() ?? 'nenhum'}</span>
      <button onClick={() => void auth.login('/integracoes')}>entrar</button>
      <button onClick={() => void auth.logout()}>sair</button>
    </div>
  );
}

describe('AuthProvider', () => {
  it('sem usuário fica anônimo e o login guarda o destino e redireciona', async () => {
    const manager = fakeManager(null);
    render(
      <AuthProvider manager={manager as never}>
        <Probe />
      </AuthProvider>,
    );

    await screen.findByText('status:anonymous');
    await userEvent.click(screen.getByText('entrar'));

    expect(manager.signinRedirect).toHaveBeenCalledOnce();
    expect(takeReturnTo()).toBe('/integracoes');
    expect(takeReturnTo()).toBe('/');
  });

  it('com usuário válido expõe o token; expirado conta como anônimo', async () => {
    const valid = fakeManager(validUser());
    const { unmount } = render(
      <AuthProvider manager={valid as never}>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText('status:authenticated');
    expect(screen.getByText('token:token-de-teste')).toBeTruthy();
    unmount();

    render(
      <AuthProvider manager={fakeManager(validUser({ expired: true })) as never}>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText('status:anonymous');
  });

  it('reage a renovação e a expiração do token', async () => {
    const manager = fakeManager(validUser());
    render(
      <AuthProvider manager={manager as never}>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText('status:authenticated');

    manager.emit('loaded', validUser({ access_token: 'novo' }));
    await screen.findByText('token:novo');
    manager.emit('expired');
    await screen.findByText('status:anonymous');
  });

  it('o logout limpa a aba e pede a saída ao provedor', async () => {
    const manager = fakeManager(validUser());
    window.sessionStorage.setItem('liberty.tenant', 'acme');
    render(
      <AuthProvider manager={manager as never}>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText('status:authenticated');

    await userEvent.click(screen.getByText('sair'));

    expect(manager.signoutRedirect).toHaveBeenCalledOnce();
    expect(window.sessionStorage.getItem('liberty.tenant')).toBeNull();
  });

  it('falha ao ler a sessão vira anônimo', async () => {
    const manager = fakeManager(null);
    manager.getUser.mockRejectedValueOnce(new Error('storage'));
    render(
      <AuthProvider manager={manager as never}>
        <Probe />
      </AuthProvider>,
    );
    await screen.findByText('status:anonymous');
  });

  it('useAuth fora do provedor falha com mensagem clara', () => {
    expect(() => render(<Probe />)).toThrow('useAuth fora do AuthProvider');
  });
});

describe('destino depois do login', () => {
  it('só aceita caminhos internos', () => {
    rememberReturnTo('//evil.example.com');
    expect(takeReturnTo()).toBe('/');
    rememberReturnTo('https://evil.example.com');
    expect(takeReturnTo()).toBe('/');
    rememberReturnTo('/ok?x=1');
    expect(takeReturnTo()).toBe('/ok?x=1');
    rememberReturnTo(undefined);
    expect(takeReturnTo()).toBe('/');
  });
});

describe('CallbackPage', () => {
  it('conclui o login e segue para o destino guardado', async () => {
    const manager = fakeManager(null);
    rememberReturnTo('/volta');
    render(
      <Wrapper path="/callback">
        <CallbackPage manager={manager} />
      </Wrapper>,
    );

    await waitFor(() => {
      expect(manager.signinRedirectCallback).toHaveBeenCalledOnce();
    });
    expect(window.sessionStorage.getItem('liberty.returnTo')).toBeNull();
  });

  it('mostra erro quando o provedor recusa o código', async () => {
    const manager = fakeManager(null);
    manager.signinRedirectCallback.mockRejectedValueOnce(new Error('invalid_grant'));
    render(
      <Wrapper path="/callback">
        <CallbackPage manager={manager} />
      </Wrapper>,
    );

    await screen.findByText('Não foi possível entrar');
  });
});

describe('configuração do login', () => {
  it('usa código de autorização (PKCE) para um cliente público com retorno na própria origem', () => {
    const manager = createUserManager(
      {
        apiUrl: '',
        oidcAuthority: 'http://idp.test/realms/liberty',
        oidcClientId: 'liberty-frontend',
      },
      'http://app.test',
    );
    const settings = manager.settings;

    expect(settings.response_type).toBe('code');
    expect(settings.client_secret).toBeUndefined();
    expect(settings.redirect_uri).toBe('http://app.test/callback');
    expect(settings.post_logout_redirect_uri).toBe('http://app.test/');
    expect(settings.scope).toBe('openid profile');
    expect(settings.automaticSilentRenew).toBe(true);
  });

  it('lê as variáveis de ambiente com padrões para desenvolvimento', () => {
    expect(loadConfig({})).toEqual({
      apiUrl: '',
      oidcAuthority: 'http://127.0.0.1:8081/realms/liberty',
      oidcClientId: 'liberty-frontend',
    });
    expect(
      loadConfig({ VITE_API_URL: 'https://api.test///', VITE_OIDC_CLIENT_ID: 'x' }).apiUrl,
    ).toBe('https://api.test');
  });
});
