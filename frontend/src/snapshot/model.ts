/** Modelo do snapshot de integração (versão 1) como a interface o edita; o backend valida de verdade. */
export type Method = 'GET' | 'POST' | 'PUT' | 'PATCH';
export type OperationType = 'SELECT' | 'INSERT' | 'UPSERT' | 'UPDATE';

export interface Param {
  name: string;
  in: 'path' | 'query';
  type: 'string' | 'integer' | 'number' | 'boolean';
  required?: boolean;
  maxLength?: number;
}

export interface Transform {
  fn: string;
  args?: unknown[];
}

export interface Field {
  column: string;
  source?: string;
  param?: string;
  constant?: unknown;
  transforms?: Transform[];
}

export interface WriteNode {
  table: string;
  arrayPath?: string;
  primaryKey?: string;
  foreignKey?: string;
  keyColumns?: string[];
  fields: Field[];
  children?: WriteNode[];
}

export interface Filter {
  param: string;
  column: string;
  operator: string;
  required?: boolean;
}

export interface Select {
  table: string;
  columns: string[];
  filters?: Filter[];
  sortable?: string[];
  defaultSort?: { column: string; direction: 'ASC' | 'DESC' };
  pageSize?: { default?: number; max?: number };
  single?: boolean;
}

export interface TestCase {
  name: string;
  request?: { params?: Record<string, unknown>; body?: unknown };
  expect: { status: number; bodyContains?: unknown };
}

export interface Snapshot {
  schemaVersion: 1;
  integration: { key: string; name: string; method: Method; route: string };
  request: { params: Param[]; bodySchema?: Record<string, unknown>; maxBodyBytes?: number };
  operation: { type: OperationType; select?: Select; write?: { root: WriteNode } };
  scripts?: { beforeExecute?: string | null; afterExecute?: string | null };
  namedQueries?: { name: string; sql: string; params?: string[] }[];
  response?: { successStatus?: number };
  idempotency?: { required: boolean };
  tests?: TestCase[];
}

export const OPERATION_BY_METHOD: Record<Method, OperationType[]> = {
  GET: ['SELECT'],
  POST: ['INSERT', 'UPSERT'],
  PUT: ['UPSERT', 'UPDATE'],
  PATCH: ['UPDATE'],
};

/** Snapshot inicial de uma integração recém-criada, coerente com o método e a rota. */
export function blankSnapshot(integration: Snapshot['integration']): Snapshot {
  const type = OPERATION_BY_METHOD[integration.method][0] ?? 'SELECT';
  const pathParams: Param[] = [...integration.route.matchAll(/\{([^}]+)\}/g)].map((m) => ({
    name: m[1] ?? '',
    in: 'path',
    type: 'string',
  }));
  const base: Snapshot = {
    schemaVersion: 1,
    integration,
    request: { params: pathParams },
    operation: { type },
  };
  if (type === 'SELECT') {
    base.operation.select = { table: '', columns: [] };
  } else {
    base.request.bodySchema = { type: 'object' };
    base.operation.write = { root: { table: '', fields: [] } };
  }
  return base;
}
