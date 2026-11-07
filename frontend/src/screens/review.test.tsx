import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { fakeManager, first, json, validUser } from '../test/helpers';
import {
  ME,
  ME_VIEWER,
  SANDBOX_OK,
  SUMMARY,
  VERSION,
  WRITE_SNAPSHOT,
  fakeApi,
} from '../test/fakeApi';
import type { Call } from '../test/fakeApi';

vi.mock('../editor/CodeEditor', () => ({
  CodeEditor: () => <div />,
  DiffViewer: (props: { original: string; modified: string }) => (
    <pre data-testid="diff">{`${props.original} => ${props.modified}`}</pre>
  ),
}));

vi.mock('react-resizable-panels', () => ({
  Group: (props: { children: React.ReactNode }) => <div>{props.children}</div>,
  Panel: (props: { children: React.ReactNode }) => <div>{props.children}</div>,
  Separator: () => <hr />,
}));

const CONFIG = { apiUrl: 'http://api.test', oidcAuthority: 'http://idp', oidcClientId: 'x' };
const BASE = '/api/v1/tenants/acme';
const KEY = `${BASE}/integrations/criar`;

const OLD_SNAPSHOT = {
  ...WRITE_SNAPSHOT,
  operation: {
    type: 'INSERT',
    write: { root: { ...WRITE_SNAPSHOT.operation.write.root, table: 'dbo.tb_pedido_antigo' } },
  },
  scripts: { afterExecute: 'function afterExecute(i) { return null; }' },
};

const LISTED = [{ ...SUMMARY[1], hmlVersionNo: 1, prdVersionNo: null }];

function routes(overrides: Record<string, unknown> = {}) {
  return {
    'GET /api/v1/me': ME,
    [`GET ${BASE}/integrations`]: LISTED,
    [`GET ${KEY}/versions`]: [VERSION(2, 'IN_REVIEW', 'carlos'), VERSION(1, 'PUBLISHED')],
    [`GET ${KEY}/versions/2/snapshot`]: WRITE_SNAPSHOT,
    [`GET ${KEY}/versions/1/snapshot`]: OLD_SNAPSHOT,
    ...overrides,
  };
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App manager={fakeManager(validUser()) as never} config={CONFIG} />
    </MemoryRouter>,
  );
}

const last = (calls: Call[], method: string, part: string) =>
  [...calls].reverse().find((c) => c.method === method && c.path.includes(part));

beforeEach(() => {
  vi.stubGlobal('location', { pathname: '/', assign: vi.fn() });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('revisão e publicação', () => {
  it('mostra o que mudou em relação ao ativo em HML e o diff dos scripts', async () => {
    fakeApi(routes());
    renderApp('/integracoes/criar/versoes/2');

    expect(await screen.findByText('criar · v2')).toBeTruthy();
    expect(screen.getByText('IN_REVIEW')).toBeTruthy();
    expect(await screen.findByText('/operation/write/root/table')).toBeTruthy();
    expect(screen.getByText('"dbo.tb_pedido_antigo"')).toBeTruthy();
    expect(screen.getByText('"dbo.tb_pedido"')).toBeTruthy();
    const diff = await screen.findByTestId('diff');
    expect(diff.textContent).toContain('return null');
    expect(diff.textContent).toContain('return i.result');
  });

  it('o aprovador publica em HML com o comentário', async () => {
    const calls = fakeApi(
      routes({ [`POST ${KEY}/versions/2/publish/HML`]: VERSION(2, 'PUBLISHED', 'carlos') }),
    );
    renderApp('/integracoes/criar/versoes/2');

    await userEvent.type(await screen.findByLabelText('Comentário'), 'tudo certo');
    await userEvent.click(screen.getByRole('button', { name: 'Aprovar e publicar em HML' }));

    await waitFor(() => {
      expect(last(calls, 'POST', '/publish/HML')?.body).toEqual({ comment: 'tudo certo' });
    });
  });

  it('o aprovador rejeita com comentário', async () => {
    const calls = fakeApi(
      routes({ [`POST ${KEY}/versions/2/reject`]: VERSION(2, 'REJECTED', 'carlos') }),
    );
    renderApp('/integracoes/criar/versoes/2');

    await userEvent.type(await screen.findByLabelText('Comentário'), 'falta teste');
    await userEvent.click(screen.getByRole('button', { name: 'Rejeitar' }));

    await waitFor(() => {
      expect(last(calls, 'POST', '/reject')?.body).toEqual({ comment: 'falta teste' });
    });
  });

  it('quem escreveu a versão não consegue aprová-la nem rejeitá-la', async () => {
    fakeApi(
      routes({
        [`GET ${KEY}/versions`]: [VERSION(2, 'IN_REVIEW', 'ana'), VERSION(1, 'PUBLISHED')],
      }),
    );
    renderApp('/integracoes/criar/versoes/2');

    expect(await screen.findByText(/quem a escreve não a aprova nem a publica/)).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Aprovar e publicar em HML' }).hasAttribute('disabled'),
    ).toBe(true);
    expect(screen.getByRole('button', { name: 'Rejeitar' }).hasAttribute('disabled')).toBe(true);
  });

  it('mostra a recusa do servidor ao publicar', async () => {
    fakeApi(
      routes({
        [`POST ${KEY}/versions/2/publish/HML`]: () =>
          json({ detail: 'A integração não passou nas conferências' }, { status: 422 }),
      }),
    );
    renderApp('/integracoes/criar/versoes/2');

    await userEvent.click(await screen.findByRole('button', { name: 'Aprovar e publicar em HML' }));

    expect(await screen.findByText(/não passou nas conferências/)).toBeTruthy();
  });

  it('versão publicada: promove para PRD, faz rollback e despublica', async () => {
    const calls = fakeApi(
      routes({
        [`GET ${KEY}/versions`]: [VERSION(1, 'PUBLISHED', 'carlos')],
        [`POST ${KEY}/versions/1/publish/PRD`]: VERSION(1, 'PUBLISHED', 'carlos'),
        [`POST ${KEY}/environments/HML/rollback`]: VERSION(1, 'PUBLISHED', 'carlos'),
        [`DELETE ${KEY}/environments/HML/publication`]: () => new Response(null, { status: 204 }),
      }),
    );
    renderApp('/integracoes/criar/versoes/1');

    expect(await screen.findByText('Sem versão de comparação: nada a comparar.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Publicar em HML' }).hasAttribute('disabled')).toBe(
      true,
    );
    expect(screen.getByRole('button', { name: 'Rollback em PRD' }).hasAttribute('disabled')).toBe(
      true,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Promover para PRD' }));
    await waitFor(() => {
      expect(last(calls, 'POST', '/publish/PRD')).toBeDefined();
    });
    await userEvent.click(screen.getByRole('button', { name: 'Rollback em HML' }));
    await waitFor(() => {
      expect(last(calls, 'POST', '/rollback')).toBeDefined();
    });
    await userEvent.click(screen.getByRole('button', { name: 'Despublicar de HML' }));
    await waitFor(() => {
      expect(last(calls, 'DELETE', '/publication')).toBeDefined();
    });
  });

  it('executa os testes da versão no sandbox e mostra o resultado', async () => {
    const calls = fakeApi(routes({ [`POST ${KEY}/versions/2/sandbox`]: SANDBOX_OK }));
    renderApp('/integracoes/criar/versoes/2');

    await userEvent.click(await screen.findByRole('button', { name: 'Executar os 1 testes' }));

    expect(await screen.findByText(/✔ cria/)).toBeTruthy();
    expect(last(calls, 'POST', '/sandbox')).toBeDefined();
  });

  it('permite trocar a versão de comparação e comparar com nada', async () => {
    fakeApi(routes());
    renderApp('/integracoes/criar/versoes/2');

    await screen.findByText('/operation/write/root/table');
    await userEvent.click(first(screen.getAllByLabelText('Comparar com')));
    await userEvent.click(
      await screen.findByRole('option', { name: 'Nada (versão inteira)', hidden: true }),
    );

    expect(await screen.findByText('Sem versão de comparação: nada a comparar.')).toBeTruthy();
  });

  it('o leitor vê a revisão sem a área de decisão', async () => {
    fakeApi(routes({ 'GET /api/v1/me': ME_VIEWER }));
    renderApp('/integracoes/criar/versoes/2');

    await screen.findByText('criar · v2');
    expect(screen.queryByText('Decisão')).toBeNull();
  });

  it('versão inexistente mostra o erro', async () => {
    fakeApi(routes());
    renderApp('/integracoes/criar/versoes/9');

    expect(await screen.findByText('Não foi possível abrir a versão')).toBeTruthy();
  });
});
