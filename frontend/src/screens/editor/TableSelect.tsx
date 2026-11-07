import { Select, TextInput } from '@mantine/core';
import { useCatalogTables } from '../../catalog/useCatalog';

/** Escolhe a tabela pelo catálogo do banco do tenant; se o catálogo não responde, aceita texto livre. */
export function TableSelect({
  slug,
  label,
  value,
  onChange,
}: {
  slug: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const tables = useCatalogTables(slug);
  if (tables.status === 'error') {
    return (
      <TextInput
        label={label}
        description="Catálogo indisponível: informe schema.tabela"
        value={value}
        onChange={(e) => {
          onChange(e.currentTarget.value);
        }}
      />
    );
  }
  const names = (tables.data ?? []).map((t) => `${t.schema}.${t.name}`);
  const data = value && !names.includes(value) ? [value, ...names] : names;
  return (
    <Select
      label={label}
      searchable
      data={data}
      value={value || null}
      placeholder={tables.status === 'loading' ? 'Carregando tabelas…' : 'Escolha a tabela'}
      onChange={(next) => {
        onChange(next ?? '');
      }}
    />
  );
}
