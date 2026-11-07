import { describe, expect, it } from 'vitest';
import { addChild, autoMapFields, depthOf, getNode, removeNode, updateNode } from './mapping';
import type { WriteNode } from './model';

const root = (): WriteNode => ({
  table: 'dbo.pedido',
  fields: [],
  children: [
    { table: 'dbo.item', arrayPath: '/itens', foreignKey: 'pedido_id', fields: [], children: [] },
  ],
});

describe('árvore de mapeamento', () => {
  it('lê, altera e adiciona nós sem mexer na árvore original', () => {
    const original = root();

    const changed = updateNode(original, [0], (n) => ({ ...n, table: 'dbo.item2' }));
    const grown = addChild(changed, [0]);

    expect(original.children?.[0]?.table).toBe('dbo.item');
    expect(getNode(changed, [0])?.table).toBe('dbo.item2');
    expect(getNode(grown, [0, 0])).toEqual({
      table: '',
      fields: [],
      arrayPath: '',
      foreignKey: '',
    });
    expect(getNode(grown, [5])).toBeUndefined();
    expect(depthOf(root())).toBe(2);
    expect(depthOf(grown)).toBe(3);
  });

  it('remove um nó filho e nunca a raiz', () => {
    const grown = addChild(root(), []);

    expect(removeNode(grown, [1]).children).toHaveLength(1);
    expect(removeNode(grown, [0]).children?.[0]?.arrayPath).toBe('');
    expect(removeNode(grown, [])).toBe(grown);
  });
});

describe('autoMapFields', () => {
  const columns = [
    {
      name: 'id',
      type: 'bigint identity',
      kind: 'INTEGER',
      nullable: false,
      primaryKey: true,
      generated: true,
      required: false,
    },
    {
      name: 'nr_pedido',
      type: 'varchar',
      kind: 'STRING',
      nullable: false,
      primaryKey: false,
      generated: false,
      required: true,
    },
    {
      name: 'cliente_id',
      type: 'int',
      kind: 'INTEGER',
      nullable: false,
      primaryKey: false,
      generated: false,
      required: true,
    },
    {
      name: 'obs',
      type: 'varchar',
      kind: 'STRING',
      nullable: true,
      primaryKey: false,
      generated: false,
      required: false,
    },
    {
      name: 'sem_par',
      type: 'varchar',
      kind: 'STRING',
      nullable: true,
      primaryKey: false,
      generated: false,
      required: false,
    },
  ];
  const pointers = [
    { pointer: '/nrPedido', type: 'string' },
    { pointer: '/clienteId', type: 'integer' },
    { pointer: '/obs', type: 'string' },
    { pointer: '/itens', type: 'array' },
  ];

  it('casa colunas graváveis com propriedades do corpo ignorando caixa e separadores', () => {
    const fields = autoMapFields(columns, pointers, [{ column: 'obs', constant: 'x' }]);

    expect(fields).toEqual([
      { column: 'obs', constant: 'x' },
      { column: 'nr_pedido', source: '/nrPedido' },
      { column: 'cliente_id', source: '/clienteId' },
    ]);
  });

  it('não repete colunas já mapeadas nem as que o motor preenche', () => {
    const fields = autoMapFields(
      columns,
      pointers,
      [{ column: 'NR_PEDIDO', source: '/x' }],
      ['cliente_id'],
    );

    expect(fields.map((f) => f.column)).toEqual(['NR_PEDIDO', 'obs']);
  });
});
