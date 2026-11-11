import { MantineProvider } from '@mantine/core';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ErrorBoundary } from './errors/ErrorBoundary';
import { fakeManager, json, validUser } from './test/helpers';

const CONFIG = {
  apiUrl: 'http://api.test',
  oidcAuthority: 'http://idp.test',
  oidcClientId: 'liberty-frontend',
};

type Handler = (url: URL) => Response | Promise<Response>;

function stubApi(routes: Record<string, Handler>) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((input: Request | URL | string) => {
      const request = input as Request;
      const url = new URL(request.url);
      calls.push(`${request.method} ${url.pathname}`);
      const handler = routes[url.pathname];
      return Promise.resolve(handler ? handler(url) : json({ detail: 'x' }, { status: 404 }));
    }),
  );
  return calls;
}

const ME = {
  sub: 'ana',
  platformAdmin: false,
  tenants: { beta: ['VIEWER'], acme: ['PUBLISHER'] },
};

const ACME = [
  { key: 'pedidos', name: 'Pedidos', method: 'GET', route: '/pedidos', archived: false },
  { key: 'criar', name: 'Criar pedido', method: 'POST', route: '/pedidos', archived: false },
];

function tenantSelect(): HTMLElement {
  const element = screen.getAllByLabelText('Tenant').at(0);
  if (!element) {
    throw new Error('Seletor de tenant não encontrado');
  }
  return element;
}

function renderApp(manager: ReturnType<typeof fakeManager>, path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App manager={manager as never} config={CONFIG} />
    </MemoryRouter>,
  );
}

const assign = vi.fn();

beforeEach(() => {
  assign.mockClear();
  vi.stubGlobal('location', { pathname: '/', assign });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('aplicação', () => {
  it('sem sessão mostra a entrada e o botão inicia o login', async () => {
    const manager = fakeManager(null);
    renderApp(manager);

    await userEvent.click(await screen.findByRole('button', { name: 'Entrar' }));

    expect(manager.signinRedirect).toHaveBeenCalledOnce();
  });

  it('login, troca de tenant e logout', async () => {
    const manager = fakeManager(validUser());
    const calls = stubApi({
      '/api/v1/me': () => json(ME),
      '/api/v1/tenants/acme/integrations': () => json(ACME),
      '/api/v1/tenants/beta/integrations': () => json([]),
    });
    renderApp(manager);

    // entra no primeiro tenant (ordem alfabética) e lista as integrações
    expect(await screen.findByText('Integrações de acme')).toBeTruthy();
    expect(await screen.findByText('Criar pedido')).toBeTruthy();
    expect(screen.getByText('ana')).toBeTruthy();

    // troca de tenant: a lista passa a ser a do outro tenant e a escolha fica na aba
    await userEvent.click(tenantSelect());
    await userEvent.click(await screen.findByRole('option', { name: 'beta', hidden: true }));
    expect(await screen.findByText('Integrações de beta')).toBeTruthy();
    expect(await screen.findByText('Nenhuma integração cadastrada neste tenant.')).toBeTruthy();
    expect(window.sessionStorage.getItem('liberty.tenant')).toBe('beta');
    expect(calls).toContain('GET /api/v1/tenants/beta/integrations');

    // sair (hidden: o Mantine ainda anima a abertura do menu e, sob carga, o item passa por um
    // instante fora da árvore de acessibilidade)
    await userEvent.click(screen.getByRole('button', { name: /ana/ }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Sair', hidden: true }));
    expect(manager.signoutRedirect).toHaveBeenCalledOnce();
    expect(window.sessionStorage.getItem('liberty.tenant')).toBeNull();
  });

  it('lembra o tenant escolhido na aba', async () => {
    window.sessionStorage.setItem('liberty.tenant', 'beta');
    stubApi({
      '/api/v1/me': () => json(ME),
      '/api/v1/tenants/beta/integrations': () => json([]),
    });
    renderApp(fakeManager(validUser()));

    expect(await screen.findByText('Integrações de beta')).toBeTruthy();
  });

  it('falha ao carregar o acesso mostra o erro e permite tentar de novo', async () => {
    let attempts = 0;
    stubApi({
      '/api/v1/me': () =>
        ++attempts === 1
          ? json({ detail: 'Banco indisponível' }, { status: 503 })
          : json({ ...ME, tenants: { acme: ['VIEWER'] } }),
      '/api/v1/tenants/acme/integrations': () => json([]),
    });
    renderApp(fakeManager(validUser()));

    expect(await screen.findByText('Banco indisponível')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(await screen.findByText('Integrações de acme')).toBeTruthy();
  });

  it('usuário sem tenant vê a orientação', async () => {
    stubApi({ '/api/v1/me': () => json({ ...ME, tenants: {} }) });
    renderApp(fakeManager(validUser()));

    expect(await screen.findByText('Sem tenant')).toBeTruthy();
    expect(screen.getByPlaceholderText('Sem tenant')).toHaveProperty('disabled', true);
  });

  it('erro na lista aparece na tela e atualizar repete a chamada', async () => {
    let attempts = 0;
    stubApi({
      '/api/v1/me': () => json({ ...ME, tenants: { acme: ['VIEWER'] } }),
      '/api/v1/tenants/acme/integrations': () =>
        ++attempts === 1 ? json({ detail: 'Sem permissão' }, { status: 403 }) : json(ACME),
    });
    renderApp(fakeManager(validUser()));

    expect(await screen.findByText('Sem permissão')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Atualizar' }));
    expect(await screen.findByText('Pedidos')).toBeTruthy();
  });

  it('401 da API leva ao login de novo', async () => {
    const manager = fakeManager(validUser());
    stubApi({
      '/api/v1/me': () => json({ detail: 'Token expirado' }, { status: 401 }),
    });
    renderApp(manager);

    await waitFor(() => {
      expect(manager.signinRedirect).toHaveBeenCalled();
    });
  });

  it('administrador da plataforma é indicado e rotas desconhecidas mostram 404', async () => {
    stubApi({
      '/api/v1/me': () => json({ ...ME, platformAdmin: true, tenants: { acme: ['VIEWER'] } }),
      '/api/v1/tenants/acme/integrations': () => json([]),
    });
    renderApp(fakeManager(validUser()), '/nada-aqui');

    expect(await screen.findByText('Página não encontrada')).toBeTruthy();
    expect(screen.getByText('Administrador da plataforma')).toBeTruthy();
    const nav = screen.getByRole('link', { name: 'Voltar ao início' });
    expect(nav.getAttribute('href')).toBe('/');
  });

  it('a rota de retorno do login conclui a sessão', async () => {
    const manager = fakeManager(null);
    renderApp(manager, '/callback');

    await waitFor(() => {
      expect(manager.signinRedirectCallback).toHaveBeenCalledOnce();
    });
  });
});

describe('ErrorBoundary', () => {
  it('troca a tela quebrada por uma de recuperação', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    function Broken(): never {
      throw new Error('boom');
    }
    render(
      <MantineProvider>
        <ErrorBoundary>
          <Broken />
        </ErrorBoundary>
      </MantineProvider>,
    );

    expect(screen.getByText('Algo deu errado')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Voltar ao início' }));
    expect(assign).toHaveBeenCalledWith('/');
  });
});
