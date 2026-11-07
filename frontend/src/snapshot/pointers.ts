type Schema = Record<string, unknown>;

export interface PointerInfo {
  pointer: string;
  type: string;
}

function typeOf(schema: Schema): string {
  const type = schema.type;
  return Array.isArray(type) ? type.join('|') : typeof type === 'string' ? type : 'any';
}

function walk(schema: Schema, prefix: string, out: PointerInfo[]) {
  const properties = schema.properties as Record<string, Schema> | undefined;
  if (!properties) {
    return;
  }
  for (const [name, child] of Object.entries(properties)) {
    const pointer = `${prefix}/${name.replace(/~/g, '~0').replace(/\//g, '~1')}`;
    out.push({ pointer, type: typeOf(child) });
    if (child.type === 'object') {
      walk(child, pointer, out);
    }
  }
}

/** Ponteiros JSON dos campos de um schema de objeto, para autocompletar o mapeamento. */
export function pointersOf(schema: Schema | undefined): PointerInfo[] {
  const out: PointerInfo[] = [];
  if (schema) {
    walk(schema, '', out);
  }
  return out;
}

/** Schema do objeto apontado por `pointer` (uma propriedade de lista devolve o schema dos itens). */
export function subschema(schema: Schema | undefined, pointer: string): Schema | undefined {
  let current: Schema | undefined = schema;
  for (const raw of pointer.split('/').filter(Boolean)) {
    const name = raw.replace(/~1/g, '/').replace(/~0/g, '~');
    const properties = current?.properties as Record<string, Schema> | undefined;
    current = properties?.[name];
    if (current?.type === 'array') {
      current = current.items as Schema | undefined;
    }
  }
  return current;
}

/** Ponteiros de lista (candidatos a `arrayPath`) dentro de um objeto do schema. */
export function arrayPointers(schema: Schema | undefined): string[] {
  return pointersOf(schema)
    .filter((p) => p.type === 'array')
    .map((p) => p.pointer);
}
