import {
  ActionIcon,
  Button,
  Group,
  MultiSelect,
  NumberInput,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useCatalogTable } from '../../catalog/useCatalog';
import type { Filter, Param, Snapshot } from '../../snapshot/model';
import { OPERATION_BY_METHOD } from '../../snapshot/model';
import { TableSelect } from './TableSelect';

const OPERATORS = ['=', '<>', '>', '>=', '<', '<=', 'LIKE_PREFIX', 'IN', 'BETWEEN'];

interface Props {
  slug: string;
  snapshot: Snapshot;
  readOnly: boolean;
  onChange: (next: Snapshot) => void;
}

/** Aba de rota e filtros: parâmetros, tipo de operação e, nas leituras, tabela, colunas e filtros. */
export function RouteTab({ slug, snapshot, readOnly, onChange }: Props) {
  const select = snapshot.operation.select;
  const table = useCatalogTable(slug, select?.table ?? '');
  const columns = (table.data?.columns ?? []).map((c) => c.name);
  const params = snapshot.request.params;

  const setParams = (next: Param[]) => {
    onChange({ ...snapshot, request: { ...snapshot.request, params: next } });
  };
  const setSelect = (next: NonNullable<Snapshot['operation']['select']>) => {
    onChange({ ...snapshot, operation: { ...snapshot.operation, select: next } });
  };
  const queryParams = params.filter((p) => p.in === 'query').map((p) => p.name);
  const filters = select?.filters ?? [];
  const setFilters = (next: Filter[]) => {
    if (select) {
      setSelect({ ...select, filters: next });
    }
  };

  return (
    <Stack>
      <Group grow align="flex-end">
        <TextInput label="Rota" value={snapshot.integration.route} readOnly />
        <Select
          label="Operação"
          data={OPERATION_BY_METHOD[snapshot.integration.method]}
          value={snapshot.operation.type}
          disabled={readOnly || OPERATION_BY_METHOD[snapshot.integration.method].length < 2}
          allowDeselect={false}
          onChange={(type) => {
            if (type) {
              onChange({
                ...snapshot,
                operation: { ...snapshot.operation, type: type },
              });
            }
          }}
        />
      </Group>

      <Text fw={600}>Parâmetros</Text>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Nome</Table.Th>
            <Table.Th>Onde</Table.Th>
            <Table.Th>Tipo</Table.Th>
            <Table.Th>Obrigatório</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {params.map((param, index) => (
            <Table.Tr key={index}>
              <Table.Td>
                <TextInput
                  aria-label={`Nome do parâmetro ${String(index + 1)}`}
                  value={param.name}
                  readOnly={readOnly}
                  onChange={(e) => {
                    setParams(
                      params.map((p, i) =>
                        i === index ? { ...p, name: e.currentTarget.value } : p,
                      ),
                    );
                  }}
                />
              </Table.Td>
              <Table.Td>
                <Select
                  aria-label={`Local do parâmetro ${String(index + 1)}`}
                  data={['path', 'query']}
                  value={param.in}
                  disabled={readOnly}
                  allowDeselect={false}
                  onChange={(where) => {
                    setParams(
                      params.map((p, i) => (i === index ? { ...p, in: where ?? 'query' } : p)),
                    );
                  }}
                />
              </Table.Td>
              <Table.Td>
                <Select
                  aria-label={`Tipo do parâmetro ${String(index + 1)}`}
                  data={['string', 'integer', 'number', 'boolean']}
                  value={param.type}
                  disabled={readOnly}
                  allowDeselect={false}
                  onChange={(type) => {
                    setParams(
                      params.map((p, i) => (i === index ? { ...p, type: type ?? 'string' } : p)),
                    );
                  }}
                />
              </Table.Td>
              <Table.Td>
                <Switch
                  aria-label={`Parâmetro ${String(index + 1)} obrigatório`}
                  checked={param.in === 'path' || (param.required ?? false)}
                  disabled={readOnly || param.in === 'path'}
                  onChange={(e) => {
                    setParams(
                      params.map((p, i) =>
                        i === index ? { ...p, required: e.currentTarget.checked } : p,
                      ),
                    );
                  }}
                />
              </Table.Td>
              <Table.Td>
                {!readOnly && (
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label={`Remover o parâmetro ${String(index + 1)}`}
                    onClick={() => {
                      setParams(params.filter((_, i) => i !== index));
                    }}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      {!readOnly && (
        <Button
          variant="default"
          leftSection={<IconPlus size={16} />}
          w="fit-content"
          onClick={() => {
            setParams([...params, { name: '', in: 'query', type: 'string' }]);
          }}
        >
          Adicionar parâmetro
        </Button>
      )}

      {select && (
        <>
          <Text fw={600}>Leitura</Text>
          <TableSelect
            slug={slug}
            label="Tabela ou view"
            value={select.table}
            onChange={(value) => {
              setSelect({ ...select, table: value, columns: [], filters: [] });
            }}
          />
          <MultiSelect
            label="Colunas devolvidas"
            data={columns}
            value={select.columns}
            disabled={readOnly}
            searchable
            onChange={(value) => {
              setSelect({ ...select, columns: value });
            }}
          />
          <Switch
            label="Devolve um único registro"
            checked={select.single ?? false}
            disabled={readOnly}
            onChange={(e) => {
              setSelect({ ...select, single: e.currentTarget.checked });
            }}
          />
          <Text fw={600}>Filtros</Text>
          {filters.map((filter, index) => (
            <Group key={index} align="flex-end" wrap="nowrap">
              <Select
                aria-label={`Parâmetro do filtro ${String(index + 1)}`}
                placeholder="Parâmetro"
                data={queryParams.concat(params.filter((p) => p.in === 'path').map((p) => p.name))}
                value={filter.param || null}
                disabled={readOnly}
                onChange={(value) => {
                  setFilters(
                    filters.map((f, i) => (i === index ? { ...f, param: value ?? '' } : f)),
                  );
                }}
              />
              <Select
                aria-label={`Coluna do filtro ${String(index + 1)}`}
                placeholder="Coluna"
                data={columns}
                searchable
                value={filter.column || null}
                disabled={readOnly}
                onChange={(value) => {
                  setFilters(
                    filters.map((f, i) => (i === index ? { ...f, column: value ?? '' } : f)),
                  );
                }}
              />
              <Select
                aria-label={`Operador do filtro ${String(index + 1)}`}
                data={OPERATORS}
                value={filter.operator}
                disabled={readOnly}
                allowDeselect={false}
                onChange={(value) => {
                  setFilters(
                    filters.map((f, i) => (i === index ? { ...f, operator: value ?? '=' } : f)),
                  );
                }}
              />
              {!readOnly && (
                <ActionIcon
                  variant="subtle"
                  color="red"
                  aria-label={`Remover o filtro ${String(index + 1)}`}
                  onClick={() => {
                    setFilters(filters.filter((_, i) => i !== index));
                  }}
                >
                  <IconTrash size={16} />
                </ActionIcon>
              )}
            </Group>
          ))}
          {!readOnly && (
            <Button
              variant="default"
              leftSection={<IconPlus size={16} />}
              w="fit-content"
              onClick={() => {
                setFilters([...filters, { param: '', column: '', operator: '=' }]);
              }}
            >
              Adicionar filtro
            </Button>
          )}
          <MultiSelect
            label="Colunas ordenáveis"
            data={columns}
            value={select.sortable ?? []}
            disabled={readOnly}
            onChange={(value) => {
              setSelect({ ...select, sortable: value });
            }}
          />
        </>
      )}

      {!select && (
        <Group align="flex-end">
          <Switch
            label="Exige o cabeçalho Idempotency-Key"
            checked={snapshot.idempotency?.required ?? false}
            disabled={readOnly}
            onChange={(e) => {
              onChange({ ...snapshot, idempotency: { required: e.currentTarget.checked } });
            }}
          />
          <NumberInput
            label="Status de sucesso"
            description="Vazio usa o padrão da operação"
            min={200}
            max={299}
            w={180}
            value={snapshot.response?.successStatus ?? ''}
            disabled={readOnly}
            onChange={(value) => {
              const next: Snapshot = { ...snapshot };
              if (typeof value === 'number') {
                next.response = { successStatus: value };
              } else {
                delete next.response;
              }
              onChange(next);
            }}
          />
        </Group>
      )}
    </Stack>
  );
}
