type Schema = Record<string, unknown>;

/** Converte um JSON Schema simples em um tipo TypeScript (para autocompletar o corpo nos scripts). */
export function schemaToTs(schema: Schema | undefined, depth = 0): string {
  if (!schema || depth > 8) {
    return 'unknown';
  }
  const type = schema.type;
  if (Array.isArray(type)) {
    return type.map((t) => schemaToTs({ ...schema, type: t }, depth + 1)).join(' | ');
  }
  switch (type) {
    case 'string':
      return 'string';
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'null':
      return 'null';
    case 'array':
      return `Array<${schemaToTs(schema.items as Schema | undefined, depth + 1)}>`;
    case 'object': {
      const properties = (schema.properties ?? {}) as Record<string, Schema>;
      const required = (schema.required ?? []) as string[];
      const members = Object.entries(properties).map(
        ([name, child]) =>
          `${JSON.stringify(name)}${required.includes(name) ? '' : '?'}: ${schemaToTs(child, depth + 1)}`,
      );
      return members.length > 0 ? `{ ${members.join('; ')} }` : 'Record<string, unknown>';
    }
    default:
      return 'unknown';
  }
}

/**
 * Declarações (.d.ts) da API que os scripts enxergam, com o corpo tipado pelo schema da versão: o
 * Monaco as usa para autocompletar e apontar erros antes de salvar.
 */
export function scriptTypings(bodySchema: Schema | undefined): string {
  const body = schemaToTs(bodySchema);
  return `
type LibertyBody = ${body};
interface Params { [name: string]: string }
// Anote beforeExecute com @param {BeforeInput} input e afterExecute com @param {AfterInput} input.
interface BeforeInput { params: Params; body: LibertyBody }
interface AfterInput { params: Params; body?: LibertyBody; result: Record<string, unknown> | { items: Array<Record<string, unknown>> } }
declare const db: {
  /** Executa uma consulta nomeada do snapshot (somente SELECT) e devolve as linhas. */
  named(name: string, params?: Record<string, unknown>): Array<Record<string, unknown>>;
  /** Valor de uma coluna da linha cuja coluna-chave vale a chave, ou null. */
  lookup(table: string, keyColumn: string, key: unknown, valueColumn: string): unknown;
};
/** Recusa a operação com um status 4xx e uma mensagem. */
declare function fail(status: number, message: string): never;
`;
}
