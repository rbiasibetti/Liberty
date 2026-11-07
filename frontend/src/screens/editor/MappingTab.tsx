import {
  ActionIcon,
  Autocomplete,
  Button,
  Card,
  Group,
  MultiSelect,
  Select,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPlus, IconTrash, IconWand } from '@tabler/icons-react';
import { useCatalogTable } from '../../catalog/useCatalog';
import type { Field, Snapshot, WriteNode } from '../../snapshot/model';
import {
  addChild,
  autoMapFields,
  depthOf,
  MAX_DEPTH,
  removeNode,
  updateNode,
} from '../../snapshot/mapping';
import type { NodePath } from '../../snapshot/mapping';
import { arrayPointers, pointersOf, subschema } from '../../snapshot/pointers';
import { TableSelect } from './TableSelect';

type Schema = Record<string, unknown> | undefined;

interface NodeProps {
  slug: string;
  node: WriteNode;
  path: NodePath;
  /** Schema do item que este nó mapeia (o corpo, para a raiz; o item da lista, para os filhos). */
  schema: Schema;
  /** Schema do nó pai: de onde saem as listas candidatas a `arrayPath`. */
  parentSchema?: Schema;
  keyed: boolean;
  readOnly: boolean;
  depth: number;
  change: (path: NodePath, update: (node: WriteNode) => WriteNode) => void;
  add: (path: NodePath) => void;
  remove: (path: NodePath) => void;
}

/** Texto de uma constante para o campo de edição (objetos ficam como JSON). */
function constantText(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function kindOf(field: Field): 'source' | 'param' | 'constant' {
  return field.param !== undefined ? 'param' : field.constant !== undefined ? 'constant' : 'source';
}

function FieldRow({
  field,
  columns,
  pointers,
  readOnly,
  onChange,
  onRemove,
}: {
  field: Field;
  columns: string[];
  pointers: string[];
  readOnly: boolean;
  onChange: (next: Field) => void;
  onRemove: () => void;
}) {
  const kind = kindOf(field);
  const retarget = (next: 'source' | 'param' | 'constant') => {
    const base = { column: field.column };
    onChange(
      next === 'source'
        ? { ...base, source: '' }
        : next === 'param'
          ? { ...base, param: '' }
          : { ...base, constant: '' },
    );
  };
  return (
    <Group align="flex-end" wrap="nowrap" gap="xs">
      <Select
        aria-label="Coluna"
        placeholder="Coluna"
        data={columns}
        searchable
        value={field.column || null}
        disabled={readOnly}
        onChange={(value) => {
          onChange({ ...field, column: value ?? '' });
        }}
      />
      <SegmentedControl
        size="xs"
        data={[
          { label: 'Corpo', value: 'source' },
          { label: 'Parâmetro', value: 'param' },
          { label: 'Constante', value: 'constant' },
        ]}
        value={kind}
        disabled={readOnly}
        onChange={(value) => {
          retarget(value);
        }}
      />
      {kind === 'source' ? (
        <Autocomplete
          aria-label="Ponteiro JSON"
          placeholder="/campo"
          data={pointers}
          value={field.source ?? ''}
          readOnly={readOnly}
          onChange={(value) => {
            onChange({ ...field, source: value });
          }}
        />
      ) : (
        <TextInput
          aria-label={kind === 'param' ? 'Parâmetro' : 'Valor constante'}
          placeholder={kind === 'param' ? 'nome do parâmetro' : 'valor'}
          value={kind === 'param' ? (field.param ?? '') : constantText(field.constant)}
          readOnly={readOnly}
          onChange={(e) => {
            const text = e.currentTarget.value;
            onChange(kind === 'param' ? { ...field, param: text } : { ...field, constant: text });
          }}
        />
      )}
      {!readOnly && (
        <ActionIcon variant="subtle" color="red" aria-label="Remover o campo" onClick={onRemove}>
          <IconTrash size={16} />
        </ActionIcon>
      )}
    </Group>
  );
}

function NodeEditor(props: NodeProps) {
  const { slug, node, path, schema, parentSchema, keyed, readOnly, depth, change, add, remove } =
    props;
  const table = useCatalogTable(slug, node.table);
  const catalogColumns = table.data?.columns ?? [];
  const writable = catalogColumns.filter((c) => !c.generated).map((c) => c.name);
  const allColumns = catalogColumns.map((c) => c.name);
  const pointers = pointersOf(schema);
  const isRoot = path.length === 0;
  const patch = (partial: Partial<WriteNode>) => {
    change(path, (current) => ({ ...current, ...partial }));
  };

  return (
    <Card withBorder padding="sm" style={{ marginLeft: depth * 16 }}>
      <Stack gap="xs">
        <Group justify="space-between">
          <Text fw={600}>
            {isRoot ? 'Tabela principal' : `Nível ${String(depth + 1)}: lista filha`}
          </Text>
          {!isRoot && !readOnly && (
            <ActionIcon
              variant="subtle"
              color="red"
              aria-label="Remover o nível"
              onClick={() => {
                remove(path);
              }}
            >
              <IconTrash size={16} />
            </ActionIcon>
          )}
        </Group>
        <TableSelect
          slug={slug}
          label="Tabela"
          value={node.table}
          onChange={(value) => {
            patch({ table: value, fields: [] });
          }}
        />
        {!isRoot && (
          <Group grow align="flex-end">
            <Autocomplete
              label="Lista no corpo (arrayPath)"
              placeholder="/itens"
              data={arrayPointers(parentSchema)}
              value={node.arrayPath ?? ''}
              readOnly={readOnly}
              onChange={(value) => {
                patch({ arrayPath: value });
              }}
            />
            <Select
              label="Coluna da chave estrangeira"
              description="Preenchida pelo motor com a chave do pai"
              data={allColumns}
              searchable
              value={
                node.foreignKey === undefined || node.foreignKey === '' ? null : node.foreignKey
              }
              disabled={readOnly}
              onChange={(value) => {
                patch({ foreignKey: value ?? '' });
              }}
            />
          </Group>
        )}
        <Group grow align="flex-end">
          <Select
            label="Chave primária gerada"
            description={
              (node.children ?? []).length > 0 || isRoot
                ? 'Devolvida na resposta e usada pelos filhos'
                : 'Só é lida se houver filhos'
            }
            data={allColumns}
            searchable
            clearable
            value={node.primaryKey === undefined || node.primaryKey === '' ? null : node.primaryKey}
            disabled={readOnly}
            onChange={(value) => {
              patch({ primaryKey: value ?? '' });
            }}
          />
          {keyed && isRoot && (
            <MultiSelect
              label="Chave completa (UPSERT e UPDATE)"
              data={allColumns}
              value={node.keyColumns ?? []}
              disabled={readOnly}
              onChange={(value) => {
                patch({ keyColumns: value });
              }}
            />
          )}
        </Group>

        <Group justify="space-between">
          <Text size="sm" fw={600}>
            Campos
          </Text>
          {!readOnly && (
            <Button
              size="xs"
              variant="light"
              leftSection={<IconWand size={14} />}
              onClick={() => {
                patch({
                  fields: autoMapFields(
                    catalogColumns,
                    pointers,
                    node.fields,
                    node.foreignKey ? [node.foreignKey] : [],
                  ),
                });
              }}
            >
              Mapear pelo nome
            </Button>
          )}
        </Group>
        {node.fields.map((field, index) => (
          <FieldRow
            key={index}
            field={field}
            columns={writable}
            pointers={pointers.map((p) => p.pointer)}
            readOnly={readOnly}
            onChange={(next) => {
              patch({ fields: node.fields.map((f, i) => (i === index ? next : f)) });
            }}
            onRemove={() => {
              patch({ fields: node.fields.filter((_, i) => i !== index) });
            }}
          />
        ))}
        {!readOnly && (
          <Button
            size="xs"
            variant="default"
            leftSection={<IconPlus size={14} />}
            w="fit-content"
            onClick={() => {
              patch({ fields: [...node.fields, { column: '', source: '' }] });
            }}
          >
            Adicionar campo
          </Button>
        )}
        {!readOnly && depth + 1 < MAX_DEPTH && (
          <Button
            size="xs"
            variant="default"
            leftSection={<IconPlus size={14} />}
            w="fit-content"
            onClick={() => {
              add(path);
            }}
          >
            Adicionar nível filho (lista)
          </Button>
        )}
        {(node.children ?? []).map((child, index) => (
          <NodeEditor
            key={index}
            {...props}
            node={child}
            path={[...path, index]}
            schema={subschema(schema, child.arrayPath ?? '')}
            parentSchema={schema}
            depth={depth + 1}
          />
        ))}
      </Stack>
    </Card>
  );
}

interface Props {
  slug: string;
  snapshot: Snapshot;
  readOnly: boolean;
  onChange: (next: Snapshot) => void;
}

/** Aba de mapeamento: a árvore de tabelas, uma por nível, e os campos de cada uma. */
export function MappingTab({ slug, snapshot, readOnly, onChange }: Props) {
  const root = snapshot.operation.write?.root;
  if (!root) {
    return <Text c="dimmed">O mapeamento vale para escritas (INSERT, UPSERT e UPDATE).</Text>;
  }
  const keyed = snapshot.operation.type === 'UPSERT' || snapshot.operation.type === 'UPDATE';
  const apply = (next: WriteNode) => {
    onChange({ ...snapshot, operation: { ...snapshot.operation, write: { root: next } } });
  };
  return (
    <Stack>
      <Text c="dimmed" size="sm">
        Cada lista do corpo vira um nível filho; a chave do pai entra na coluna de chave estrangeira
        do filho. Até {String(MAX_DEPTH)} níveis (atual: {String(depthOf(root))}).
      </Text>
      <NodeEditor
        slug={slug}
        node={root}
        path={[]}
        schema={snapshot.request.bodySchema}
        keyed={keyed}
        readOnly={readOnly}
        depth={0}
        change={(path, update) => {
          apply(updateNode(root, path, update));
        }}
        add={(path) => {
          apply(addChild(root, path));
        }}
        remove={(path) => {
          apply(removeNode(root, path));
        }}
      />
    </Stack>
  );
}
