type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type Schema = Record<string, unknown>;

function merge(a: Schema, b: Schema): Schema {
  if (a.type === 'object' && b.type === 'object') {
    const pa = (a.properties ?? {}) as Record<string, Schema>;
    const pb = (b.properties ?? {}) as Record<string, Schema>;
    const keys = new Set([...Object.keys(pa), ...Object.keys(pb)]);
    const properties: Record<string, Schema> = {};
    for (const key of keys) {
      const left = pa[key];
      const right = pb[key];
      properties[key] = left && right ? merge(left, right) : (left ?? right ?? {});
    }
    const required = ((a.required ?? []) as string[]).filter((k) =>
      ((b.required ?? []) as string[]).includes(k),
    );
    return { type: 'object', properties, ...(required.length > 0 ? { required } : {}) };
  }
  if (a.type === 'array' && b.type === 'array') {
    return { type: 'array', items: merge((a.items ?? {}) as Schema, (b.items ?? {}) as Schema) };
  }
  if (a.type === b.type) {
    return a;
  }
  const types = new Set<unknown>([
    ...(Array.isArray(a.type) ? (a.type as unknown[]) : [a.type]),
    ...(Array.isArray(b.type) ? (b.type as unknown[]) : [b.type]),
  ]);
  if (types.has('integer') && types.has('number')) {
    types.delete('integer');
  }
  return types.size === 1 ? { type: [...types][0] } : { type: [...types] };
}

function infer(value: Json): Schema {
  if (value === null) {
    return { type: 'null' };
  }
  if (Array.isArray(value)) {
    const items = value
      .map(infer)
      .reduce<Schema | undefined>((acc, cur) => (acc ? merge(acc, cur) : cur), undefined);
    return { type: 'array', items: items ?? {} };
  }
  switch (typeof value) {
    case 'object': {
      const properties: Record<string, Schema> = {};
      for (const [key, child] of Object.entries(value)) {
        properties[key] = infer(child);
      }
      const required = Object.keys(properties);
      return { type: 'object', properties, ...(required.length > 0 ? { required } : {}) };
    }
    case 'number':
      return { type: Number.isInteger(value) ? 'integer' : 'number' };
    case 'boolean':
      return { type: 'boolean' };
    default:
      return { type: 'string' };
  }
}

/**
 * Gera um JSON Schema (2020-12) a partir de um JSON de exemplo: tipos, propriedades presentes como
 * obrigatórias e itens de lista unificados. É um ponto de partida para o integrador refinar
 * (limites, formatos, o que é opcional).
 */
export function schemaFromSample(sample: unknown): Schema {
  return { $schema: 'https://json-schema.org/draft/2020-12/schema', ...infer(sample as Json) };
}
