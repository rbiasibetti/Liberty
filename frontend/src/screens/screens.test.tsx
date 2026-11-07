import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import { fakeManager, first, json, nth, validUser } from '../test/helpers';
import {
  ITEM_COLUMNS,
  ME,
  ME_VIEWER,
  PEDIDO_COLUMNS,
  READ_SNAPSHOT,
  SANDBOX_OK,
  SUMMARY,
  TABLES,
  VERSION,
  WRITE_SNAPSHOT,
  fakeApi,
} from '../test/fakeApi';
import type { Call } from '../test/fakeApi';

vi.mock('../editor/CodeEditor', () => ({
  CodeEditor: (props: {
    label: string;
    value: string;
    readOnly?: boolean;
    onChange?: (value: string) => void;
  }) => (
    <textarea
      aria-label={props.label}
      value={props.value}
      readOnly={props.readOnly}
      onChange={(e) => props.onChange?.(e.currentTarget.value)}
    />
  ),
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

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App manager={fakeManager(validUser()) as never} config={CONFIG} />
    </MemoryRouter>,
  );
}

function catalogRoutes() {
  return {
    [`GET ${BASE}/environments/HML/catalog/tables`]: TABLES,
    [`GET ${BASE}/environments/HML/catalog/tables/dbo.tb_pedido`]: PEDIDO_COLUMNS,
    [`GET ${BASE}/environments/HML/catalog/tables/dbo.tb_item`]: ITEM_COLUMNS,
  };
}

function writeRoutes(extra: Record<string, unknown> = {}) {
  return {
    'GET /api/v1/me': ME,
    [`GET ${BASE}/integrations`]: SUMMARY,
    [`GET ${BASE}/integrations/criar/versions`]: [VERSION(1, 'DRAFT')],
    [`GET ${BASE}/integrations/criar/versions/1/snapshot`]: WRITE_SNAPSHOT,
    ...catalogRoutes(),
    ...extra,
  };
}

const lastCall = (calls: Call[], method: string, pathPart: string) =>
  [...calls].reverse().find((c) => c.method === method && c.path.includes(pathPart));

beforeEach(() => {
  vi.stubGlobal('location', { pathname: '/', assign: vi.fn() });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('lista de integrações', () => {
  it('mostra o publicado em cada ambiente, o rascunho e a saúde das últimas 24 h', async () => {
    fakeApi({ 'GET /api/v1/me': ME, [`GET ${BASE}/integrations`]: SUMMARY });
    renderApp('/');

    const row = (await screen.findByText('pedidos')).closest('tr') as HTMLElement;

    expect(within(row).getByText('v1')).toBeTruthy();
    expect(within(row).getByText('v2')).toBeTruthy();
    expect(within(row).getByText('120 chamadas')).toBeTruthy();
    expect(within(row).getByText('3 com erro 5xx')).toBeTruthy();
  });

  it('cria uma integração e abre o editor com um esqueleto coerente', async () => {
    let created = false;
    const calls = fakeApi({
      'GET /api/v1/me': ME,
      [`GET ${BASE}/integrations`]: () =>
        json(
          created
            ? [...SUMMARY, { ...SUMMARY[1], key: 'nova', name: 'Nova', route: '/nova' }]
            : SUMMARY,
        ),
      [`POST ${BASE}/integrations`]: () => {
        created = true;
        return json({ ...SUMMARY[1], key: 'nova', name: 'Nova', route: '/nova' }, { status: 201 });
      },
      [`GET ${BASE}/integrations/nova/versions`]: [],
      ...catalogRoutes(),
    });
    renderApp('/');

    await userEvent.click(await screen.findByRole('button', { name: 'Nova integração' }));
    await userEvent.type(screen.getByLabelText('Chave'), 'nova');
    await userEvent.type(screen.getByLabelText('Nome'), 'Nova');
    await userEvent.clear(screen.getByLabelText('Rota'));
    await userEvent.type(screen.getByLabelText('Rota'), '/nova');
    await userEvent.click(screen.getByRole('button', { name: 'Criar' }));

    expect(await screen.findByRole('button', { name: 'Salvar rascunho' })).toBeTruthy();
    expect(lastCall(calls, 'POST', '/integrations')?.body).toEqual({
      key: 'nova',
      name: 'Nova',
      method: 'GET',
      route: '/nova',
    });
  });

  it('recusa a criação com o erro da API', async () => {
    fakeApi({
      'GET /api/v1/me': ME,
      [`GET ${BASE}/integrations`]: SUMMARY,
      [`POST ${BASE}/integrations`]: () =>
        json({ detail: 'Já existe uma integração' }, { status: 409 }),
    });
    renderApp('/');

    await userEvent.click(await screen.findByRole('button', { name: 'Nova integração' }));
    await userEvent.type(screen.getByLabelText('Chave'), 'pedidos');
    await userEvent.type(screen.getByLabelText('Nome'), 'Pedidos');
    await userEvent.click(screen.getByRole('button', { name: 'Criar' }));

    expect(await screen.findByText(/Já existe uma integração/)).toBeTruthy();
  });

  it('o leitor não vê o botão de criar', async () => {
    fakeApi({ 'GET /api/v1/me': ME_VIEWER, [`GET ${BASE}/integrations`]: SUMMARY });
    renderApp('/');

    await screen.findByText('pedidos');
    expect(screen.queryByRole('button', { name: 'Nova integração' })).toBeNull();
  });
});

describe('editor: mapeamento e salvamento', () => {
  it('mapeia pelo nome, acrescenta e remove níveis, salva e envia para revisão', async () => {
    const calls = fakeApi(
      writeRoutes({
        [`PUT ${BASE}/integrations/criar/draft`]: VERSION(1, 'DRAFT'),
        [`POST ${BASE}/integrations/criar/versions/1/submit`]: VERSION(1, 'IN_REVIEW'),
        [`GET ${BASE}/integrations/criar/versions/1/snapshot`]: WRITE_SNAPSHOT,
      }),
    );
    renderApp('/integracoes/criar');

    expect(await screen.findByText('v1 · DRAFT')).toBeTruthy();
    await userEvent.click(screen.getByRole('tab', { name: 'Mapeamento' }));
    expect(await screen.findByText('Tabela principal')).toBeTruthy();
    expect(screen.getByText('Nível 2: lista filha')).toBeTruthy();

    // "Mapear pelo nome" traz cliente_id (clienteId no corpo) para a raiz
    await userEvent.click(
      first(await screen.findAllByRole('button', { name: /Mapear pelo nome/ })),
    );
    expect(await screen.findByText('alterações não salvas')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: 'Enviar para revisão' }).hasAttribute('disabled'),
    ).toBe(true);

    // acrescenta e remove um nível filho
    await userEvent.click(
      nth(await screen.findAllByRole('button', { name: /Adicionar nível filho/ }), 1),
    );
    expect(await screen.findByText('Nível 3: lista filha')).toBeTruthy();
    await userEvent.click(nth(screen.getAllByRole('button', { name: 'Remover o nível' }), 1));
    await waitFor(() => {
      expect(screen.queryByText('Nível 3: lista filha')).toBeNull();
    });

    await userEvent.click(screen.getByRole('button', { name: 'Salvar rascunho' }));
    await waitFor(() => {
      expect(screen.queryByText('alterações não salvas')).toBeNull();
    });
    const saved = lastCall(calls, 'PUT', '/draft')?.body as {
      operation: { write: { root: { fields: { column: string; source: string }[] } } };
    };
    expect(saved.operation.write.root.fields).toContainEqual({
      column: 'cliente_id',
      source: '/clienteId',
    });

    await userEvent.click(screen.getByRole('button', { name: 'Enviar para revisão' }));
    await waitFor(() => {
      expect(lastCall(calls, 'POST', '/submit')).toBeDefined();
    });
  });

  it('mostra a recusa do servidor ao salvar', async () => {
    fakeApi(
      writeRoutes({
        [`PUT ${BASE}/integrations/criar/draft`]: () =>
          json({ detail: 'A integração não passou nas conferências' }, { status: 422 }),
      }),
    );
    renderApp('/integracoes/criar');

    await userEvent.click(await screen.findByRole('tab', { name: 'Scripts' }));
    const script = await screen.findByLabelText('afterExecute');
    await userEvent.clear(script);
    await userEvent.type(script, 'x');
    await userEvent.click(screen.getByRole('button', { name: 'Salvar rascunho' }));

    expect(await screen.findByText(/não passou nas conferências/)).toBeTruthy();
    expect(screen.getByText('alterações não salvas')).toBeTruthy();
  });

  it('o JSON Schema pode ser gerado de um exemplo e o texto inválido não derruba a edição', async () => {
    fakeApi(writeRoutes());
    renderApp('/integracoes/criar');

    await userEvent.click(await screen.findByRole('tab', { name: 'JSON Schema' }));
    const editor = await screen.findByLabelText('JSON Schema do corpo');
    await userEvent.type(editor, '{{{{');
    expect(await screen.findByText(/ainda não é um JSON de objeto válido/)).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Gerar a partir de um exemplo' }));
    const sample = await screen.findByLabelText('JSON de exemplo');
    await userEvent.clear(sample);
    await userEvent.type(sample, 'isto não é json');
    await userEvent.click(screen.getByRole('button', { name: 'Gerar' }));
    expect(await screen.findByText('O exemplo não é um JSON válido.')).toBeTruthy();

    await userEvent.clear(sample);
    await userEvent.type(sample, '{{"codigo": "A", "qtd": 2}');
    await userEvent.click(screen.getByRole('button', { name: 'Gerar' }));
    await waitFor(() => {
      expect(screen.getByLabelText<HTMLTextAreaElement>('JSON Schema do corpo').value).toContain(
        '"qtd"',
      );
    });
  });

  it('scripts e testes: edita, adiciona um teste e roda os testes no sandbox', async () => {
    const calls = fakeApi(
      writeRoutes({
        [`POST ${BASE}/integrations/criar/versions/1/sandbox`]: SANDBOX_OK,
      }),
    );
    renderApp('/integracoes/criar');

    await userEvent.click(await screen.findByRole('tab', { name: 'Testes' }));
    expect(await screen.findByText('cria')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Executar os testes' }));
    expect(await screen.findByText(/✔ cria/)).toBeTruthy();
    expect((lastCall(calls, 'POST', '/sandbox')?.body as { body: unknown }).body).toEqual({
      nrPedido: 'P-1',
    });

    await userEvent.click(screen.getByRole('button', { name: 'Adicionar teste' }));
    expect(await screen.findByText('Teste 2')).toBeTruthy();
    expect(screen.getByText('alterações não salvas')).toBeTruthy();
    expect(screen.getByText('Salve o rascunho para executar os testes.')).toBeTruthy();
    await userEvent.click(screen.getByText('Teste 2'));
    await userEvent.click(await screen.findByRole('button', { name: 'Remover o teste 2' }));
    await waitFor(() => {
      expect(screen.queryByText('Teste 2')).toBeNull();
    });
  });

  it('o sandbox mostra resultado, SQL e console; falhas da integração aparecem como problema', async () => {
    let failing = false;
    fakeApi(
      writeRoutes({
        [`POST ${BASE}/integrations/criar/versions/1/sandbox`]: () =>
          json(
            failing
              ? {
                  ...SANDBOX_OK,
                  succeeded: false,
                  status: 409,
                  body: null,
                  logs: '',
                  problem: {
                    status: 409,
                    type: 'urn:liberty:problem:conflict',
                    detail: 'Já existe um registro com esta chave',
                    violations: [{ parameter: '/nrPedido', message: 'duplicado' }],
                  },
                }
              : SANDBOX_OK,
          ),
      }),
    );
    renderApp('/integracoes/criar');

    await screen.findByText('v1 · DRAFT');
    await userEvent.click(screen.getByRole('button', { name: 'Executar' }));
    expect(await screen.findByText(/status 201 · 12 ms · desfeito/)).toBeTruthy();
    expect(screen.getByText(/INSERT INTO \[dbo\]\.\[tb_pedido\]/)).toBeTruthy();
    expect(screen.getByText('ola do console')).toBeTruthy();

    failing = true;
    await userEvent.click(screen.getByRole('button', { name: 'Executar' }));
    expect(await screen.findByText('Já existe um registro com esta chave')).toBeTruthy();
    expect(screen.getByText('/nrPedido: duplicado')).toBeTruthy();

    const body = screen.getByLabelText('Corpo (JSON)');
    await userEvent.clear(body);
    await userEvent.type(body, 'quebrado');
    expect(screen.getByRole('button', { name: 'Executar' }).hasAttribute('disabled')).toBe(true);
  });
});

describe('editor: rota e filtros', () => {
  const readRoutes = (extra: Record<string, unknown> = {}) => ({
    'GET /api/v1/me': ME,
    [`GET ${BASE}/integrations`]: SUMMARY,
    [`GET ${BASE}/integrations/pedidos/versions`]: [VERSION(2, 'DRAFT'), VERSION(1, 'PUBLISHED')],
    [`GET ${BASE}/integrations/pedidos/versions/2/snapshot`]: READ_SNAPSHOT,
    ...catalogRoutes(),
    ...extra,
  });

  it('edita parâmetros e filtros de uma leitura', async () => {
    fakeApi(readRoutes());
    renderApp('/integracoes/pedidos');

    await screen.findByText('v2 · DRAFT');
    expect(screen.getByLabelText('Nome do parâmetro 1')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar parâmetro' }));
    expect(await screen.findByLabelText('Nome do parâmetro 2')).toBeTruthy();
    await userEvent.type(screen.getByLabelText('Nome do parâmetro 2'), 'status');
    await userEvent.click(screen.getByRole('button', { name: 'Adicionar filtro' }));
    expect((await screen.findAllByLabelText('Operador do filtro 2')).length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: 'Remover o filtro 2' }));
    await userEvent.click(screen.getByRole('button', { name: 'Remover o parâmetro 2' }));

    await waitFor(() => {
      expect(screen.queryByLabelText('Nome do parâmetro 2')).toBeNull();
    });
    expect(screen.getByText('alterações não salvas')).toBeTruthy();
    // leituras não têm corpo nem mapeamento
    await userEvent.click(screen.getByRole('tab', { name: 'JSON Schema' }));
    expect(await screen.findByText(/Leituras não têm corpo/)).toBeTruthy();
    await userEvent.click(screen.getByRole('tab', { name: 'Mapeamento' }));
    expect(await screen.findByText(/O mapeamento vale para escritas/)).toBeTruthy();
  });

  it('com o catálogo fora do ar aceita o nome da tabela digitado', async () => {
    fakeApi(
      readRoutes({
        [`GET ${BASE}/environments/HML/catalog/tables`]: () =>
          json({ detail: 'O banco do cliente está indisponível neste ambiente' }, { status: 503 }),
      }),
    );
    renderApp('/integracoes/pedidos');

    expect(await screen.findByText(/Catálogo indisponível/)).toBeTruthy();
  });

  it('o leitor vê o editor sem botões de salvar nem de enviar', async () => {
    fakeApi(readRoutes({ 'GET /api/v1/me': ME_VIEWER }));
    renderApp('/integracoes/pedidos');

    await screen.findByText('v2 · DRAFT');
    expect(screen.queryByRole('button', { name: 'Salvar rascunho' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Adicionar parâmetro' })).toBeNull();
  });

  it('integração inexistente mostra o erro', async () => {
    fakeApi({
      'GET /api/v1/me': ME,
      [`GET ${BASE}/integrations`]: SUMMARY,
      [`GET ${BASE}/integrations/nada/versions`]: [],
    });
    renderApp('/integracoes/nada');

    expect(await screen.findByText('Não foi possível abrir a integração')).toBeTruthy();
  });
});
