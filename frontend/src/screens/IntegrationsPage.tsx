import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useCallback, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../api/apiContext';
import { useRequest } from '../api/useRequest';
import { notifyError } from '../errors/notify';
import { useTenant } from '../tenant/tenantContext';

function Version({ no }: { no: number | null | undefined }) {
  return no ? <Badge variant="light">{`v${String(no)}`}</Badge> : <Text c="dimmed">—</Text>;
}

function NewIntegration({ slug, onClose }: { slug: string; onClose: () => void }) {
  const api = useApi();
  const navigate = useNavigate();
  const [key, setKey] = useState('');
  const [name, setName] = useState('');
  const [method, setMethod] = useState<string | null>('GET');
  const [route, setRoute] = useState('/');
  const [busy, setBusy] = useState(false);

  const create = () => {
    setBusy(true);
    api
      .POST('/api/v1/tenants/{slug}/integrations', {
        params: { path: { slug } },
        body: { key, name, method: (method ?? 'GET') as 'GET', route },
      })
      .then(
        ({ data }) => {
          onClose();
          void navigate(`/integracoes/${data?.key ?? key}`);
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'Não foi possível criar a integração');
        },
      );
  };

  return (
    <Modal opened onClose={onClose} title="Nova integração">
      <Stack>
        <TextInput
          label="Chave"
          description="Letras minúsculas, números e hífen"
          value={key}
          onChange={(e) => {
            setKey(e.currentTarget.value);
          }}
        />
        <TextInput
          label="Nome"
          value={name}
          onChange={(e) => {
            setName(e.currentTarget.value);
          }}
        />
        <Select
          label="Método"
          data={['GET', 'POST', 'PUT', 'PATCH']}
          value={method}
          onChange={setMethod}
          allowDeselect={false}
        />
        <TextInput
          label="Rota"
          description="Variáveis entre chaves, por exemplo /pedidos/{numero}"
          value={route}
          onChange={(e) => {
            setRoute(e.currentTarget.value);
          }}
        />
        <Button onClick={create} loading={busy} disabled={!key || !name}>
          Criar
        </Button>
      </Stack>
    </Modal>
  );
}

/** Tela 1: as integrações do tenant, o que está publicado em cada ambiente e a saúde das últimas 24 h. */
export function IntegrationsPage() {
  const api = useApi();
  const { current, can } = useTenant();
  const [creating, setCreating] = useState(false);
  const fetchItems = useCallback(async () => {
    if (!current) {
      return [];
    }
    const { data } = await api.GET('/api/v1/tenants/{slug}/integrations', {
      params: { path: { slug: current } },
    });
    return data ?? [];
  }, [api, current]);
  const request = useRequest(`integrations:${current ?? ''}`, fetchItems);

  if (!current) {
    return (
      <Alert color="yellow" title="Sem tenant">
        Seu usuário ainda não tem acesso a nenhum tenant. Peça a um administrador.
      </Alert>
    );
  }
  const items = request.status === 'ready' ? request.data : undefined;
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Integrações de {current}</Title>
        <Group>
          <Button variant="default" onClick={request.reload}>
            Atualizar
          </Button>
          {can('INTEGRATOR') && (
            <Button
              onClick={() => {
                setCreating(true);
              }}
            >
              Nova integração
            </Button>
          )}
        </Group>
      </Group>
      {request.status === 'error' ? (
        <Alert color="red" title="Não foi possível carregar">
          {request.error}
        </Alert>
      ) : items === undefined ? (
        <Loader aria-label="Carregando integrações" />
      ) : items.length === 0 ? (
        <Text c="dimmed">Nenhuma integração cadastrada neste tenant.</Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Chave</Table.Th>
              <Table.Th>Rota</Table.Th>
              <Table.Th>HML</Table.Th>
              <Table.Th>PRD</Table.Th>
              <Table.Th>Rascunho</Table.Th>
              <Table.Th>Últimas 24 h</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {items.map((item) => (
              <Table.Tr key={item.key}>
                <Table.Td>
                  <Link to={`/integracoes/${item.key}`}>{item.key}</Link>
                  <Text size="xs" c="dimmed">
                    {item.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Badge variant="outline" mr="xs">
                    {item.method}
                  </Badge>
                  <code>{item.route}</code>
                </Table.Td>
                <Table.Td>
                  <Version no={item.hmlVersionNo} />
                </Table.Td>
                <Table.Td>
                  <Version no={item.prdVersionNo} />
                </Table.Td>
                <Table.Td>
                  <Version no={item.draftVersionNo} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{`${String(item.calls24h)} chamadas`}</Text>
                  <Text size="xs" c={item.errors24h > 0 ? 'red' : 'dimmed'}>
                    {`${String(item.errors24h)} com erro 5xx`}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {creating && (
        <NewIntegration
          slug={current}
          onClose={() => {
            setCreating(false);
          }}
        />
      )}
    </Stack>
  );
}
