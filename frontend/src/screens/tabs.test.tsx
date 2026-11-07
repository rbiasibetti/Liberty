import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiContext } from '../api/apiContext';
import { createApiClient } from '../api/client';
import type { Snapshot } from '../snapshot/model';
import { first, Wrapper } from '../test/helpers';
import {
  ITEM_COLUMNS,
  PEDIDO_COLUMNS,
  READ_SNAPSHOT,
  TABLES,
  WRITE_SNAPSHOT,
  fakeApi,
} from '../test/fakeApi';
import { MappingTab } from './editor/MappingTab';
import { RouteTab } from './editor/RouteTab';
import { TestsTab } from './editor/TestsTab';

const BASE = '/api/v1/tenants/acme/environments/HML/catalog/tables';

const client = () =>
  createApiClient({
    baseUrl: 'http://api.test',
    getToken: () => 't',
    onUnauthorized: () => undefined,
  });

function stubCatalog() {
  fakeApi({
    [`GET ${BASE}`]: TABLES,
    [`GET ${BASE}/dbo.tb_pedido`]: PEDIDO_COLUMNS,
    [`GET ${BASE}/dbo.tb_item`]: ITEM_COLUMNS,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

function Harness({
  initial,
  readOnly = false,
  tab,
  spy,
}: {
  initial: unknown;
  readOnly?: boolean;
  tab: 'mapping' | 'route' | 'tests';
  spy?: (next: Snapshot) => void;
}) {
  const [snapshot, setSnapshot] = useState(initial as Snapshot);
  const change = (next: Snapshot) => {
    setSnapshot(next);
    spy?.(next);
  };
  return (
    <Wrapper>
      <ApiContext.Provider value={client()}>
        {tab === 'mapping' && (
          <MappingTab slug="acme" snapshot={snapshot} readOnly={readOnly} onChange={change} />
        )}
        {tab === 'route' && (
          <RouteTab slug="acme" snapshot={snapshot} readOnly={readOnly} onChange={change} />
        )}
        {tab === 'tests' && (
          <TestsTab
            slug="acme"
            integrationKey="criar"
            versionNo={1}
            snapshot={snapshot}
            readOnly={readOnly}
            onChange={change}
          />
        )}
      </ApiContext.Provider>
    </Wrapper>
  );
}

const latest = (spy: ReturnType<typeof vi.fn>) => spy.mock.calls.at(-1)?.[0] as Snapshot;

describe('aba de mapeamento', () => {
  it('troca a origem de um campo (corpo, parâmetro, constante), acrescenta e remove campos', async () => {
    stubCatalog();
    const spy = vi.fn();
    render(<Harness initial={WRITE_SNAPSHOT} tab="mapping" spy={spy} />);

    await screen.findByText('Tabela principal');
    const params = await screen.findAllByText('Parâmetro');
    await userEvent.click(first(params));
    await userEvent.type(await screen.findByPlaceholderText('nome do parâmetro'), 'cliente');
    expect(latest(spy).operation.write?.root.fields[0]).toEqual({
      column: 'nr_pedido',
      param: 'cliente',
    });

    await userEvent.click(first(await screen.findAllByText('Constante')));
    await userEvent.type(await screen.findByPlaceholderText('valor'), 'X');
    expect(latest(spy).operation.write?.root.fields[0]).toEqual({
      column: 'nr_pedido',
      constant: 'X',
    });

    await userEvent.click(first(await screen.findAllByText('Corpo')));
    await userEvent.click(first(await screen.findAllByRole('button', { name: 'Adicionar campo' })));
    expect(latest(spy).operation.write?.root.fields).toHaveLength(2);
    await userEvent.click(first(await screen.findAllByRole('button', { name: 'Remover o campo' })));
    expect(latest(spy).operation.write?.root.fields).toHaveLength(1);
  });

  it('em UPSERT pede a chave completa e a tabela pode ser trocada', async () => {
    stubCatalog();
    const spy = vi.fn();
    const upsert = {
      ...WRITE_SNAPSHOT,
      operation: { ...WRITE_SNAPSHOT.operation, type: 'UPSERT' },
    };
    render(<Harness initial={upsert} tab="mapping" spy={spy} />);

    expect(await screen.findByText('Chave completa (UPSERT e UPDATE)')).toBeTruthy();
    await userEvent.click(first(await screen.findAllByLabelText('Tabela')));
    await userEvent.click(
      first(await screen.findAllByRole('option', { name: 'dbo.tb_item', hidden: true })),
    );
    await waitFor(() => {
      expect(latest(spy).operation.write?.root.table).toBe('dbo.tb_item');
    });
    expect(latest(spy).operation.write?.root.fields).toEqual([]);
  });

  it('só leitura: sem botões de edição', async () => {
    stubCatalog();
    render(<Harness initial={WRITE_SNAPSHOT} tab="mapping" readOnly />);

    await screen.findByText('Tabela principal');
    expect(screen.queryByRole('button', { name: 'Adicionar campo' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Mapear pelo nome/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remover o nível' })).toBeNull();
  });
});

describe('aba de rota e filtros', () => {
  it('altera local, tipo e obrigatoriedade dos parâmetros e opções da leitura', async () => {
    stubCatalog();
    const spy = vi.fn();
    render(<Harness initial={READ_SNAPSHOT} tab="route" spy={spy} />);

    await screen.findByLabelText('Nome do parâmetro 1');
    await userEvent.click(screen.getByLabelText('Parâmetro 1 obrigatório'));
    expect(latest(spy).request.params[0]?.required).toBe(true);

    await userEvent.click(screen.getByLabelText('Devolve um único registro'));
    expect(latest(spy).operation.select?.single).toBe(true);

    await userEvent.type(screen.getByLabelText('Nome do parâmetro 1'), 'x');
    expect(latest(spy).request.params[0]?.name).toBe('clientex');

    await userEvent.click(await screen.findByRole('button', { name: 'Adicionar filtro' }));
    expect(latest(spy).operation.select?.filters).toHaveLength(2);
  });

  it('em escrita mostra idempotência e status de sucesso', async () => {
    stubCatalog();
    const spy = vi.fn();
    render(<Harness initial={WRITE_SNAPSHOT} tab="route" spy={spy} />);

    await userEvent.click(await screen.findByLabelText('Exige o cabeçalho Idempotency-Key'));
    expect(latest(spy).idempotency).toEqual({ required: true });

    const status = screen.getByLabelText('Status de sucesso');
    await userEvent.type(status, '202');
    expect(latest(spy).response).toEqual({ successStatus: 202 });
    await userEvent.clear(status);
    expect(latest(spy).response).toBeUndefined();
  });

  it('só leitura: campos travados e sem botões', async () => {
    stubCatalog();
    render(<Harness initial={READ_SNAPSHOT} tab="route" readOnly />);

    await screen.findByLabelText('Nome do parâmetro 1');
    expect(screen.queryByRole('button', { name: 'Adicionar parâmetro' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remover o filtro 1' })).toBeNull();
  });
});

describe('aba de testes', () => {
  it('edita nome, entrada e expectativas de um teste', async () => {
    stubCatalog();
    const spy = vi.fn();
    render(<Harness initial={WRITE_SNAPSHOT} tab="tests" spy={spy} />);

    await userEvent.click(await screen.findByText('cria'));
    const name = await screen.findByRole('textbox', { name: 'Nome' });
    await userEvent.clear(name);
    await userEvent.type(name, 'cria ok');
    expect(latest(spy).tests?.[0]?.name).toBe('cria ok');

    const body = screen.getByLabelText('Corpo (JSON)');
    await userEvent.clear(body);
    await userEvent.type(body, '{{"a": 1}');
    expect(latest(spy).tests?.[0]?.request?.body).toEqual({ a: 1 });
    await userEvent.type(body, 'x');
    expect(await screen.findByText('JSON inválido')).toBeTruthy();

    await userEvent.type(screen.getByLabelText('Parâmetros (JSON)'), '{{"p": "1"}');
    expect(latest(spy).tests?.[0]?.request?.params).toEqual({ p: '1' });

    const expected = screen.getByLabelText('O corpo deve conter (JSON)');
    await userEvent.type(expected, '{{"ok": true}');
    expect(latest(spy).tests?.[0]?.expect.bodyContains).toEqual({ ok: true });

    const status = screen.getByLabelText('Status esperado');
    await userEvent.clear(status);
    await userEvent.type(status, '409');
    expect(latest(spy).tests?.[0]?.expect.status).toBe(409);
  });
});
