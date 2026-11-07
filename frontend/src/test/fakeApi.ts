import { vi } from 'vitest';
import { json } from './helpers';

export interface Call {
  method: string;
  path: string;
  body: unknown;
}

type Handler = (call: Call) => Response | Promise<Response>;

/** API falsa: rotas por "MÉTODO /caminho"; devolve as chamadas feitas para as conferências. */
export function fakeApi(routes: Record<string, unknown>) {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: Request | URL | string) => {
      const request = input as Request;
      const url = new URL(request.url);
      const text = request.method === 'GET' ? '' : await request.clone().text();
      const call: Call = {
        method: request.method,
        path: url.pathname,
        body: text ? (JSON.parse(text) as unknown) : undefined,
      };
      calls.push(call);
      const route = routes[`${request.method} ${url.pathname}`];
      if (route === undefined) {
        return json({ detail: `sem rota ${request.method} ${url.pathname}` }, { status: 404 });
      }
      return typeof route === 'function' ? (route as Handler)(call) : json(route);
    }),
  );
  return calls;
}

export const ME = { sub: 'ana', platformAdmin: false, tenants: { acme: ['PUBLISHER'] } };

export const ME_INTEGRATOR = {
  sub: 'ana',
  platformAdmin: false,
  tenants: { acme: ['INTEGRATOR'] },
};

export const ME_VIEWER = { sub: 'ana', platformAdmin: false, tenants: { acme: ['VIEWER'] } };

export const SUMMARY = [
  {
    key: 'pedidos',
    name: 'Pedidos',
    method: 'GET',
    route: '/pedidos',
    archived: false,
    draftVersionNo: 2,
    hmlVersionNo: 1,
    prdVersionNo: null,
    calls24h: 120,
    errors24h: 3,
  },
  {
    key: 'criar',
    name: 'Criar pedido',
    method: 'POST',
    route: '/pedidos',
    archived: false,
    draftVersionNo: null,
    hmlVersionNo: null,
    prdVersionNo: null,
    calls24h: 0,
    errors24h: 0,
  },
];

export const READ_SNAPSHOT = {
  schemaVersion: 1,
  integration: { key: 'pedidos', name: 'Pedidos', method: 'GET', route: '/pedidos' },
  request: { params: [{ name: 'cliente', in: 'query', type: 'integer' }] },
  operation: {
    type: 'SELECT',
    select: {
      table: 'dbo.tb_pedido',
      columns: ['id', 'nr_pedido'],
      filters: [{ param: 'cliente', column: 'cliente_id', operator: '=' }],
    },
  },
};

export const WRITE_SNAPSHOT = {
  schemaVersion: 1,
  integration: { key: 'criar', name: 'Criar pedido', method: 'POST', route: '/pedidos' },
  request: {
    params: [],
    bodySchema: {
      type: 'object',
      properties: {
        nrPedido: { type: 'string' },
        clienteId: { type: 'integer' },
        itens: {
          type: 'array',
          items: {
            type: 'object',
            properties: { produto: { type: 'string' }, qtd: { type: 'integer' } },
          },
        },
      },
    },
  },
  operation: {
    type: 'INSERT',
    write: {
      root: {
        table: 'dbo.tb_pedido',
        primaryKey: 'id',
        fields: [{ column: 'nr_pedido', source: '/nrPedido' }],
        children: [
          {
            table: 'dbo.tb_item',
            arrayPath: '/itens',
            foreignKey: 'pedido_id',
            fields: [{ column: 'produto', source: '/produto' }],
          },
        ],
      },
    },
  },
  scripts: { afterExecute: 'function afterExecute(i) { return i.result; }' },
  tests: [{ name: 'cria', request: { body: { nrPedido: 'P-1' } }, expect: { status: 201 } }],
};

export const TABLES = [
  { schema: 'dbo', name: 'tb_pedido', kind: 'TABLE' },
  { schema: 'dbo', name: 'tb_item', kind: 'TABLE' },
];

export const columns = (names: [string, boolean][]) =>
  names.map(([name, generated]) => ({
    name,
    type: generated ? 'bigint identity' : 'varchar',
    kind: generated ? 'INTEGER' : 'STRING',
    nullable: !generated,
    primaryKey: generated,
    generated,
    required: !generated,
  }));

export const PEDIDO_COLUMNS = {
  schema: 'dbo',
  name: 'tb_pedido',
  kind: 'TABLE',
  columns: columns([
    ['id', true],
    ['nr_pedido', false],
    ['cliente_id', false],
  ]),
};

export const ITEM_COLUMNS = {
  schema: 'dbo',
  name: 'tb_item',
  kind: 'TABLE',
  columns: columns([
    ['id', true],
    ['pedido_id', false],
    ['produto', false],
    ['qtd', false],
  ]),
};

export const VERSION = (no: number, state: string, author = 'ana') => ({
  versionNo: no,
  state,
  author,
  reviewer: state === 'PUBLISHED' ? 'bia' : null,
  comment: null,
  createdAt: '2026-11-07T10:00:00Z',
  reviewedAt: null,
  sha256: 'ab'.repeat(32),
});

export const SANDBOX_OK = {
  succeeded: true,
  status: 201,
  body: { key: { id: 7 }, rows: { tb_pedido: 1 } },
  problem: null,
  sql: [{ sql: 'INSERT INTO [dbo].[tb_pedido] ([nr_pedido]) VALUES (:p0)', rows: 1, millis: 3 }],
  sqlTruncated: false,
  logs: 'ola do console',
  millis: 12,
  rolledBack: true,
};
