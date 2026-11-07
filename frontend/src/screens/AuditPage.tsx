import {
  Alert,
  Badge,
  Button,
  Code,
  Drawer,
  Group,
  Loader,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useCallback, useState } from 'react';
import { useApi } from '../api/apiContext';
import type { components } from '../api/schema';
import { useRequest } from '../api/useRequest';
import { useTenant } from '../tenant/tenantContext';

type Row = components['schemas']['AuditRow'];

const PERIODS: Record<string, number> = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };

function statusColor(status: number): string {
  return status >= 500 ? 'red' : status >= 400 ? 'orange' : 'green';
}

/** Tela 5: auditoria de execuções, com filtros e uma gaveta de detalhes (só metadados e erro mascarado). */
export function AuditPage() {
  const api = useApi();
  const { current } = useTenant();
  const [period, setPeriod] = useState<string>('24h');
  const [integration, setIntegration] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [requestId, setRequestId] = useState('');
  const [applied, setApplied] = useState({
    period: '24h',
    integration: '',
    status: '',
    requestId: '',
  });
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Row | null>(null);

  const integrations = useRequest(
    `audit-integrations:${current ?? ''}`,
    useCallback(async () => {
      if (!current) {
        return [];
      }
      const { data } = await api.GET('/api/v1/tenants/{slug}/integrations', {
        params: { path: { slug: current } },
      });
      return (data ?? []).map((i) => i.key);
    }, [api, current]),
  );

  const search = useCallback(async () => {
    if (!current) {
      return undefined;
    }
    const hours = PERIODS[applied.period] ?? 24;
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3_600_000);
    const { data } = await api.GET('/api/v1/tenants/{slug}/audit/executions', {
      params: {
        path: { slug: current },
        query: {
          from: from.toISOString(),
          to: to.toISOString(),
          ...(applied.integration ? { integration: applied.integration } : {}),
          ...(applied.status ? { status: applied.status } : {}),
          ...(applied.requestId ? { requestId: applied.requestId } : {}),
          page,
          size: 25,
        },
      },
    });
    return data;
  }, [api, current, applied, page]);
  const result = useRequest(
    `audit:${current ?? ''}:${JSON.stringify(applied)}:${String(page)}`,
    search,
  );

  if (!current) {
    return (
      <Alert color="yellow" title="Sem tenant">
        Escolha um tenant para consultar a auditoria.
      </Alert>
    );
  }
  const apply = () => {
    setPage(0);
    setApplied({
      period,
      integration: integration ?? '',
      status: status ?? '',
      requestId: requestId.trim(),
    });
  };
  const data = result.status === 'ready' ? result.data : undefined;

  return (
    <Stack>
      <Title order={2}>Auditoria de {current}</Title>
      <Group align="flex-end">
        <Select
          label="Período"
          w={140}
          allowDeselect={false}
          data={[
            { value: '1h', label: 'Última hora' },
            { value: '24h', label: 'Últimas 24 h' },
            { value: '7d', label: 'Últimos 7 dias' },
            { value: '30d', label: 'Últimos 30 dias' },
          ]}
          value={period}
          onChange={(value) => {
            setPeriod(value ?? '24h');
          }}
        />
        <Select
          label="Integração"
          w={200}
          clearable
          searchable
          data={integrations.data ?? []}
          value={integration}
          onChange={setIntegration}
        />
        <Select
          label="Status"
          w={120}
          clearable
          data={['2xx', '4xx', '5xx']}
          value={status}
          onChange={setStatus}
        />
        <TextInput
          label="request_id"
          w={240}
          value={requestId}
          onChange={(e) => {
            setRequestId(e.currentTarget.value);
          }}
        />
        <Button onClick={apply}>Filtrar</Button>
      </Group>
      {result.status === 'error' ? (
        <Alert color="red" title="Não foi possível consultar">
          {result.error}
        </Alert>
      ) : data === undefined ? (
        <Loader aria-label="Consultando a auditoria" />
      ) : data.items.length === 0 ? (
        <Text c="dimmed">Nenhuma execução encontrada neste período.</Text>
      ) : (
        <>
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Quando (UTC)</Table.Th>
                <Table.Th>Integração</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Duração</Table.Th>
                <Table.Th>request_id</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.items.map((row) => (
                <Table.Tr
                  key={row.id}
                  style={{ cursor: 'pointer' }}
                  onClick={() => {
                    setSelected(row);
                  }}
                >
                  <Table.Td>{row.createdAt.replace('T', ' ').slice(0, 19)}</Table.Td>
                  <Table.Td>
                    {row.integration} <Badge variant="outline">{row.environment}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Badge color={statusColor(row.status)}>{row.status}</Badge>
                  </Table.Td>
                  <Table.Td>{`${String(row.durationMs)} ms`}</Table.Td>
                  <Table.Td>
                    <code>{row.requestId}</code>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group justify="space-between">
            <Button
              variant="default"
              disabled={page === 0}
              onClick={() => {
                setPage((p) => p - 1);
              }}
            >
              Anterior
            </Button>
            <Text size="sm" c="dimmed">{`Página ${String(page + 1)}`}</Text>
            <Button
              variant="default"
              disabled={!data.hasMore}
              onClick={() => {
                setPage((p) => p + 1);
              }}
            >
              Próxima
            </Button>
          </Group>
        </>
      )}
      <Drawer
        opened={selected !== null}
        onClose={() => {
          setSelected(null);
        }}
        position="right"
        size="lg"
        title="Detalhes da execução"
      >
        {selected && (
          <Stack gap="xs">
            <Group>
              <Badge color={statusColor(selected.status)}>{selected.status}</Badge>
              <Text>{`${selected.method} ${selected.route}`}</Text>
            </Group>
            <Text size="sm">{`Integração: ${selected.integration} (${selected.environment})`}</Text>
            <Text size="sm">{`Quando (UTC): ${selected.createdAt}`}</Text>
            <Text size="sm">{`Duração: ${String(selected.durationMs)} ms · banco: ${String(selected.dbDurationMs ?? '—')} ms · scripts: ${String(selected.scriptDurationMs ?? '—')} ms`}</Text>
            <Text size="sm">{`Cliente: ${selected.clientId ?? '—'}`}</Text>
            <Text size="sm">
              request_id: <Code>{selected.requestId}</Code>
            </Text>
            {selected.errorCode && (
              <Alert color="red" title={selected.errorCode}>
                {selected.errorMessage ?? 'Sem mensagem.'}
              </Alert>
            )}
            <Text size="xs" c="dimmed">
              A auditoria guarda só metadados e o erro com dados pessoais mascarados; o corpo da
              requisição e o SQL não são gravados. Para ver o SQL de uma versão, use o sandbox.
            </Text>
          </Stack>
        )}
      </Drawer>
    </Stack>
  );
}
