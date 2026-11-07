import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { fakeManager, first, json, nth, validUser } from '../test/helpers';
import { ME, SUMMARY, fakeApi } from '../test/fakeApi';
import type { Call } from '../test/fakeApi';

vi.mock('../editor/CodeEditor', () => ({ CodeEditor: () => <div />, DiffViewer: () => <div /> }));
vi.mock('react-resizable-panels', () => ({
  Group: (p: { children: React.ReactNode }) => <div>{p.children}</div>,
  Panel: (p: { children: React.ReactNode }) => <div>{p.children}</div>,
  Separator: () => <hr />,
}));

const CONFIG = { apiUrl: 'http://api.test', oidcAuthority: 'http://idp', oidcClientId: 'x' };
const T = '/api/v1/tenants/acme';
const P = '/api/v1/platform/tenants/acme';
const ADMIN = { sub: 'root', platformAdmin: true, tenants: {} };

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App manager={fakeManager(validUser()) as never} config={CONFIG} />
    </MemoryRouter>,
  );
}

const last = (calls: Call[], method: string, part: string) =>
  [...calls].reverse().find((c) => c.method === method && c.path.includes(part));

const row = (id: number, status: number, extra: Record<string, unknown> = {}) => ({
  id,
  createdAt: '2026-11-07T12:00:00Z',
  integration: 'pedidos',
  environment: 'HML',
  method: 'GET',
  route: '/pedidos',
  status,
  durationMs: 15,
  dbDurationMs: 4,
  scriptDurationMs: null,
  errorCode: null,
  errorMessage: null,
  requestId: `req-${String(id)}`,
  clientId: 'cli',
  ...extra,
});

beforeEach(() => {
  vi.stubGlobal('location', { pathname: '/', assign: vi.fn() });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('auditoria', () => {
  it('lista, filtra, pagina e abre a gaveta com o erro mascarado', async () => {
    const calls = fakeApi({
      'GET /api/v1/me': ME,
      [`GET ${T}/integrations`]: SUMMARY,
      [`GET ${T}/audit/executions`]: (call: Call) =>
        json({
          items: [
            row(2, 503, {
              errorCode: 'tenant-unavailable',
              errorMessage: 'contato ***@*** indisponível',
            }),
            row(1, 200),
          ],
          page: 0,
          size: 25,
          hasMore: call.path.length > 0,
        }),
    });
    renderApp('/auditoria');

    expect(await screen.findByText('req-2')).toBeTruthy();
    expect(screen.getByText('503')).toBeTruthy();

    await userEvent.type(screen.getByLabelText('request_id'), 'req-2');
    await userEvent.click(screen.getByRole('button', { name: 'Filtrar' }));
    await waitFor(() => {
      expect(String(last(calls, 'GET', '/audit/executions')?.path)).toContain('/audit/executions');
    });
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }));
    expect(await screen.findByText('Página 2')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByText('Página 1')).toBeTruthy();

    await userEvent.click(screen.getByText('req-2'));
    expect(await screen.findByText('Detalhes da execução')).toBeTruthy();
    expect(await screen.findByText('contato ***@*** indisponível')).toBeTruthy();
    expect(screen.getByText(/o corpo da requisição e o SQL não são gravados/)).toBeTruthy();
  });

  it('mostra período vazio e o erro da consulta', async () => {
    let failing = false;
    fakeApi({
      'GET /api/v1/me': ME,
      [`GET ${T}/integrations`]: SUMMARY,
      [`GET ${T}/audit/executions`]: () =>
        failing
          ? json({ detail: 'A janela de consulta é de no máximo 31 dias' }, { status: 400 })
          : json({ items: [], page: 0, size: 25, hasMore: false }),
    });
    renderApp('/auditoria');

    expect(await screen.findByText('Nenhuma execução encontrada neste período.')).toBeTruthy();
    failing = true;
    await userEvent.click(screen.getByRole('button', { name: 'Filtrar' }));
    expect(await screen.findByText(/no máximo 31 dias/)).toBeTruthy();
  });

  it('o leitor não tem o item de auditoria no menu', async () => {
    fakeApi({
      'GET /api/v1/me': { ...ME, tenants: { acme: ['VIEWER'] } },
      [`GET ${T}/integrations`]: SUMMARY,
    });
    renderApp('/');

    await screen.findByText('pedidos');
    expect(screen.queryByText('Auditoria')).toBeNull();
  });
});

describe('administração do tenant', () => {
  it('quem não é administrador da plataforma não entra', async () => {
    fakeApi({ 'GET /api/v1/me': ME, [`GET ${T}/integrations`]: SUMMARY });
    renderApp('/administracao');

    expect(await screen.findByText('Acesso restrito')).toBeTruthy();
    expect(screen.queryByText('Administração')).toBeNull();
  });

  const adminRoutes = (extra: Record<string, unknown> = {}) => ({
    'GET /api/v1/me': ADMIN,
    [`GET ${P}/connections`]: [
      {
        environment: 'HML',
        jdbcUrl: 'jdbc:sqlserver://h:1433;encrypt=true',
        dbUsername: 'app',
        poolMax: 4,
        status: 'ACTIVE',
      },
    ],
    [`GET ${P}/clients`]: [
      {
        clientId: 'erp',
        environment: 'HML',
        scopes: ['integration:execute:pedidos'],
        status: 'ACTIVE',
        createdAt: '2026-11-07T10:00:00Z',
      },
      {
        clientId: 'velho',
        environment: 'PRD',
        scopes: ['integration:execute:x'],
        status: 'REVOKED',
        createdAt: '2026-01-01T10:00:00Z',
      },
    ],
    [`GET ${P}/users`]: [{ sub: 'ana', roles: ['INTEGRATOR', 'VIEWER'] }],
    ...extra,
  });

  async function openAdmin() {
    renderApp('/administracao');
    const tenant = await screen.findByRole('textbox', { name: /Tenant/ });
    await userEvent.type(tenant, 'acme{Enter}');
  }

  it('conexões: mostra o cadastro, salva sem exibir a senha e testa a conexão', async () => {
    const calls = fakeApi(
      adminRoutes({
        [`PUT ${P}/connections/PRD`]: {
          environment: 'PRD',
          jdbcUrl: 'u',
          dbUsername: 'u',
          poolMax: 5,
          status: 'ACTIVE',
        },
        [`POST ${P}/connections/HML/test`]: () =>
          json({ ok: true, millis: 12, message: 'Conexão estabelecida' }),
      }),
    );
    await openAdmin();

    expect(await screen.findByText('Homologação (HML)')).toBeTruthy();
    expect(screen.getByText('cadastrada')).toBeTruthy();
    expect(screen.getByText('não cadastrada')).toBeTruthy();

    await userEvent.click(first(screen.getAllByRole('button', { name: 'Testar conexão' })));
    expect(await screen.findByText(/Conexão estabelecida \(12 ms\)/)).toBeTruthy();

    const urls = screen.getAllByLabelText('URL JDBC');
    await userEvent.type(nth(urls, 1), 'jdbc:sqlserver://prd:1433;encrypt=true');
    await userEvent.type(nth(screen.getAllByLabelText('Usuário do banco'), 1), 'app');
    await userEvent.type(nth(screen.getAllByLabelText('Senha'), 1), 'segredo!');
    await userEvent.click(nth(screen.getAllByRole('button', { name: 'Salvar' }), 1));

    await waitFor(() => {
      expect(last(calls, 'PUT', '/connections/PRD')?.body).toMatchObject({
        jdbcUrl: 'jdbc:sqlserver://prd:1433;encrypt=true',
        dbUsername: 'app',
        password: 'segredo!',
      });
    });
    expect(document.body.textContent).not.toContain('segredo!');
  });

  it('conexões: erro de validação do servidor aparece e a falha do teste não vaza detalhes', async () => {
    fakeApi(
      adminRoutes({
        [`PUT ${P}/connections/HML`]: () =>
          json({ detail: 'A conexão do tenant exige encrypt=true' }, { status: 400 }),
        [`POST ${P}/connections/HML/test`]: () =>
          json({
            ok: false,
            millis: 5,
            message: 'Não foi possível conectar com os dados informados',
          }),
      }),
    );
    await openAdmin();

    await screen.findByText('Homologação (HML)');
    await userEvent.click(first(screen.getAllByRole('button', { name: 'Salvar' })));
    expect(await screen.findByText(/exige encrypt=true/)).toBeTruthy();
    await userEvent.click(first(screen.getAllByRole('button', { name: 'Testar conexão' })));
    expect(await screen.findByText('Falha na conexão')).toBeTruthy();
  });

  it('clientes: cria mostrando o segredo uma vez e revoga', async () => {
    const calls = fakeApi(
      adminRoutes({
        [`POST ${P}/clients`]: () =>
          json(
            {
              client: {
                clientId: 'novo',
                environment: 'HML',
                scopes: ['integration:execute:pedidos'],
                status: 'ACTIVE',
                createdAt: '2026-11-07T10:00:00Z',
              },
              clientSecret: 'segredo-gerado-123',
            },
            { status: 201 },
          ),
        [`DELETE ${P}/clients/erp`]: () => new Response(null, { status: 204 }),
      }),
    );
    await openAdmin();

    await userEvent.click(await screen.findByRole('tab', { name: 'Clientes OAuth2' }));
    expect(await screen.findByText('erp')).toBeTruthy();
    expect(screen.getByText('REVOKED')).toBeTruthy();
    expect(screen.queryByLabelText('Revogar velho')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Novo cliente' }));
    await userEvent.type(await screen.findByLabelText('Identificador'), 'novo');
    await userEvent.type(
      first(screen.getAllByLabelText('Integrações permitidas')),
      'pedidos{Enter}',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Criar' }));

    expect(await screen.findByText('segredo-gerado-123')).toBeTruthy();
    expect(last(calls, 'POST', '/clients')?.body).toEqual({
      clientId: 'novo',
      environment: 'HML',
      scopes: ['integration:execute:pedidos'],
    });
    await userEvent.click(screen.getByRole('button', { name: 'Já guardei' }));
    await waitFor(() => {
      expect(screen.queryByText('segredo-gerado-123')).toBeNull();
    });

    await userEvent.click(screen.getByLabelText('Revogar erp'));
    await waitFor(() => {
      expect(last(calls, 'DELETE', '/clients/erp')).toBeDefined();
    });
  });

  it('usuários: concede e retira papéis', async () => {
    const calls = fakeApi(
      adminRoutes({
        [`PUT ${P}/users/bia/roles/PUBLISHER`]: () => new Response(null, { status: 204 }),
        [`DELETE ${P}/users/ana/roles/VIEWER`]: () => new Response(null, { status: 204 }),
      }),
    );
    await openAdmin();

    await userEvent.click(await screen.findByRole('tab', { name: 'Usuários e papéis' }));
    expect(await screen.findByRole('cell', { name: 'ana' })).toBeTruthy();

    await userEvent.type(screen.getByLabelText('Usuário (sub do provedor)'), 'bia');
    await userEvent.click(first(screen.getAllByLabelText('Papel')));
    await userEvent.click(
      first(await screen.findAllByRole('option', { name: 'PUBLISHER', hidden: true })),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Conceder' }));
    await waitFor(() => {
      expect(last(calls, 'PUT', '/users/bia/roles/PUBLISHER')).toBeDefined();
    });

    await userEvent.click(screen.getByLabelText('Retirar VIEWER de ana'));
    await waitFor(() => {
      expect(last(calls, 'DELETE', '/users/ana/roles/VIEWER')).toBeDefined();
    });
  });
});
