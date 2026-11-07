import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../api/client';
import { diffDocuments, diffSnapshots } from './diff';
import { blankSnapshot, OPERATION_BY_METHOD } from './model';
import { arrayPointers, pointersOf, subschema } from './pointers';
import { schemaFromSample } from './schemaFromSample';
import { schemaToTs, scriptTypings } from './scriptTypes';
import { contains, runTests } from './testRunner';

describe('blankSnapshot', () => {
  it('cria o esqueleto coerente com o método e declara os parâmetros de caminho', () => {
    const read = blankSnapshot({ key: 'a', name: 'A', method: 'GET', route: '/a/{id}/b/{n}' });
    const write = blankSnapshot({ key: 'b', name: 'B', method: 'POST', route: '/b' });

    expect(read.operation.type).toBe('SELECT');
    expect(read.operation.select).toEqual({ table: '', columns: [] });
    expect(read.request.params.map((p) => p.name)).toEqual(['id', 'n']);
    expect(write.operation.type).toBe('INSERT');
    expect(write.operation.write?.root.table).toBe('');
    expect(write.request.bodySchema).toEqual({ type: 'object' });
    expect(OPERATION_BY_METHOD.PATCH).toEqual(['UPDATE']);
  });
});

describe('schemaFromSample', () => {
  it('infere tipos, obrigatórios e itens de lista unificados', () => {
    const schema = schemaFromSample({
      numero: 'P-1',
      total: 10.5,
      qtd: 2,
      ativo: true,
      obs: null,
      itens: [
        { produto: 'A', qtd: 1, lote: 'x' },
        { produto: 'B', qtd: 2.5 },
      ],
    });

    expect(schema.$schema).toContain('2020-12');
    const properties = schema.properties as Record<string, Record<string, unknown>>;
    expect(properties.numero).toEqual({ type: 'string' });
    expect(properties.total).toEqual({ type: 'number' });
    expect(properties.qtd).toEqual({ type: 'integer' });
    expect(properties.ativo).toEqual({ type: 'boolean' });
    expect(properties.obs).toEqual({ type: 'null' });
    const items = properties.itens?.items as Record<string, unknown>;
    expect((items.properties as Record<string, unknown>).qtd).toEqual({ type: 'number' });
    expect(items.required).toEqual(['produto', 'qtd']);
  });

  it('trata listas vazias, escalares e tipos mistos', () => {
    expect(schemaFromSample([])).toMatchObject({ type: 'array', items: {} });
    expect(schemaFromSample('x')).toMatchObject({ type: 'string' });
    expect(schemaFromSample({})).toEqual({
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {},
    });
    const mixed = schemaFromSample([1, 'a', null]) as { items: { type: string[] } };
    expect(mixed.items.type).toEqual(['integer', 'string', 'null']);
  });
});

describe('pointers', () => {
  const schema = {
    type: 'object',
    properties: {
      numero: { type: 'string' },
      'a/b': { type: 'string' },
      cliente: { type: 'object', properties: { id: { type: 'integer' } } },
      itens: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            produto: { type: 'string' },
            lotes: {
              type: 'array',
              items: { type: 'object', properties: { codigo: { type: 'string' } } },
            },
          },
        },
      },
    },
  };

  it('lista ponteiros, escapando / e ~', () => {
    expect(pointersOf(schema).map((p) => p.pointer)).toEqual([
      '/numero',
      '/a~1b',
      '/cliente',
      '/cliente/id',
      '/itens',
    ]);
    expect(pointersOf(undefined)).toEqual([]);
  });

  it('acha o subschema de um objeto ou dos itens de uma lista, e as listas candidatas', () => {
    expect(Object.keys(subschema(schema, '/itens')?.properties ?? {})).toEqual([
      'produto',
      'lotes',
    ]);
    expect(Object.keys(subschema(subschema(schema, '/itens'), '/lotes')?.properties ?? {})).toEqual(
      ['codigo'],
    );
    expect(subschema(schema, '/nada')).toBeUndefined();
    expect(subschema(undefined, '/x')).toBeUndefined();
    expect(arrayPointers(schema)).toEqual(['/itens']);
  });
});

describe('diff', () => {
  it('aponta adições, remoções e mudanças com o ponteiro de cada uma', () => {
    const changes = diffDocuments(
      { a: 1, b: { c: [1, 2], d: 'x' }, gone: true },
      { a: 2, b: { c: [1, 2, 3], d: 'x' }, novo: 1 },
    );

    expect(changes).toEqual([
      { path: '/a', kind: 'changed', before: 1, after: 2 },
      { path: '/b/c/2', kind: 'added', after: 3 },
      { path: '/gone', kind: 'removed', before: true },
      { path: '/novo', kind: 'added', after: 1 },
    ]);
    expect(diffDocuments({ l: [1, 2] }, { l: [1] })).toEqual([
      { path: '/l/1', kind: 'removed', before: 2 },
    ]);
    expect(diffDocuments(1, 1)).toEqual([]);
    expect(diffDocuments('a', 'b')).toEqual([
      { path: '/', kind: 'changed', before: 'a', after: 'b' },
    ]);
  });

  it('separa os scripts para o diff de texto', () => {
    const diff = diffSnapshots(
      { x: 1, scripts: { beforeExecute: 'function beforeExecute(){}' } },
      {
        x: 2,
        scripts: { beforeExecute: 'function beforeExecute(){ return 1; }', afterExecute: 'f' },
      },
    );

    expect(diff.changes).toEqual([{ path: '/x', kind: 'changed', before: 1, after: 2 }]);
    expect(diff.scripts.beforeExecute[1]).toContain('return 1');
    expect(diff.scripts.afterExecute).toEqual(['', 'f']);
    expect(diffSnapshots(null, null).scripts.beforeExecute).toEqual(['', '']);
  });
});

describe('scriptTypings', () => {
  it('tipa o corpo pelo schema e declara a API dos scripts', () => {
    const body = schemaToTs({
      type: 'object',
      required: ['numero'],
      properties: {
        numero: { type: 'string' },
        total: { type: ['number', 'null'] },
        itens: {
          type: 'array',
          items: { type: 'object', properties: { qtd: { type: 'integer' } } },
        },
        ok: { type: 'boolean' },
      },
    });

    expect(body).toContain('"numero": string');
    expect(body).toContain('"total"?: number | null');
    expect(body).toContain('Array<{ "qtd"?: number }>');
    expect(schemaToTs(undefined)).toBe('unknown');
    expect(schemaToTs({ type: 'object' })).toBe('Record<string, unknown>');
    expect(schemaToTs({ type: 'weird' })).toBe('unknown');
    const typings = scriptTypings({ type: 'object', properties: { x: { type: 'string' } } });
    expect(typings).toContain('declare const db');
    expect(typings).toContain('declare const http');
    expect(typings).toContain('interface HttpResponse');
    expect(typings).toContain('declare function fail(status: number, message: string): never');
    expect(typings).toContain('interface BeforeInput');
    expect(typings).toContain('"x"?: string');
  });
});

describe('contains', () => {
  it('confere objetos e listas por inclusão', () => {
    expect(contains({ a: 1 }, { a: 1, b: 2 })).toBe(true);
    expect(contains({ a: 2 }, { a: 1 })).toBe(false);
    expect(contains([{ id: 1 }], [{ id: 2 }, { id: 1, x: 1 }])).toBe(true);
    expect(contains([1], 'x')).toBe(false);
    expect(contains({ a: 1 }, null)).toBe(false);
    expect(contains('x', 'x')).toBe(true);
  });
});

describe('runTests', () => {
  function apiReturning(...replies: unknown[]) {
    const post = vi.fn();
    replies.forEach((reply) => {
      if (reply instanceof Error) {
        post.mockRejectedValueOnce(reply);
      } else {
        post.mockResolvedValueOnce({ data: reply });
      }
    });
    return { api: { POST: post } as unknown as ApiClient, post };
  }

  it('confere status e corpo esperados e relata cada falha', async () => {
    const { api, post } = apiReturning(
      { status: 201, body: { key: { id: 1 } } },
      { status: 400, body: null },
      { status: 201, body: { key: { id: 1 } } },
      new TypeError('rede'),
      undefined,
    );

    const results = await runTests(api, 'acme', 'pedidos', 3, [
      {
        name: 'ok',
        request: { body: { a: 1 } },
        expect: { status: 201, bodyContains: { key: { id: 1 } } },
      },
      { name: 'status', expect: { status: 201 } },
      { name: 'corpo', expect: { status: 201, bodyContains: { key: { id: 2 } } } },
      { name: 'rede', expect: { status: 200 } },
      { name: 'vazio', expect: { status: 200 } },
    ]);

    expect(results.map((r) => r.passed)).toEqual([true, false, false, false, false]);
    expect(results[1]?.reason).toContain('veio 400');
    expect(results[2]?.reason).toContain('corpo');
    expect(results[3]?.reason).toContain('conexão');
    expect(post).toHaveBeenCalledWith(
      '/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/sandbox',
      expect.objectContaining({ params: { path: { slug: 'acme', key: 'pedidos', no: 3 } } }),
    );
  });
});
