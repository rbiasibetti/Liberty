import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Code,
  CopyButton,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  TagsInput,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconTrash } from '@tabler/icons-react';
import { useCallback, useState } from 'react';
import { useApi } from '../../api/apiContext';
import { useRequest } from '../../api/useRequest';
import { notifyError } from '../../errors/notify';

function NewClient({
  slug,
  onClose,
  onCreated,
}: {
  slug: string;
  onClose: () => void;
  onCreated: () => void;
}) {
  const api = useApi();
  const [clientId, setClientId] = useState('');
  const [environment, setEnvironment] = useState<string | null>('HML');
  const [scopes, setScopes] = useState<string[]>([]);
  const [secret, setSecret] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const create = () => {
    setBusy(true);
    api
      .POST('/api/v1/platform/tenants/{slug}/clients', {
        params: { path: { slug } },
        body: {
          clientId,
          environment: (environment ?? 'HML') as 'HML',
          scopes: scopes.map((s) =>
            s.startsWith('integration:execute:') ? s : `integration:execute:${s}`,
          ),
        },
      })
      .then(
        ({ data }) => {
          setSecret(data?.clientSecret ?? null);
          setBusy(false);
          onCreated();
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'O cliente não foi criado');
        },
      );
  };

  return (
    <Modal
      opened
      onClose={onClose}
      title="Novo cliente OAuth2"
      closeOnClickOutside={secret === null}
    >
      {secret ? (
        <Stack>
          <Alert color="yellow" title="Guarde o segredo agora">
            Este segredo aparece uma única vez; depois só dá para criar outro cliente.
          </Alert>
          <Code block>{secret}</Code>
          <CopyButton value={secret}>
            {({ copied, copy }) => (
              <Button onClick={copy} variant="default">
                {copied ? 'Copiado' : 'Copiar o segredo'}
              </Button>
            )}
          </CopyButton>
          <Button onClick={onClose}>Já guardei</Button>
        </Stack>
      ) : (
        <Stack>
          <TextInput
            label="Identificador"
            description="Letras minúsculas, números e hífen (3 a 61)"
            value={clientId}
            onChange={(e) => {
              setClientId(e.currentTarget.value);
            }}
          />
          <Select
            label="Ambiente"
            data={['HML', 'PRD']}
            value={environment}
            onChange={setEnvironment}
            allowDeselect={false}
          />
          <TagsInput
            label="Integrações permitidas"
            description="Chaves das integrações; vira integration:execute:<chave>"
            value={scopes}
            onChange={setScopes}
          />
          <Button onClick={create} loading={busy} disabled={!clientId || scopes.length === 0}>
            Criar
          </Button>
        </Stack>
      )}
    </Modal>
  );
}

/** Clientes OAuth2 de sistema: criar (segredo exibido uma vez) e revogar. */
export function ClientsTab({ slug }: { slug: string }) {
  const api = useApi();
  const [creating, setCreating] = useState(false);
  const fetchAll = useCallback(async () => {
    const { data } = await api.GET('/api/v1/platform/tenants/{slug}/clients', {
      params: { path: { slug } },
    });
    return data ?? [];
  }, [api, slug]);
  const request = useRequest(`clients:${slug}`, fetchAll);

  const revoke = (clientId: string) => {
    api
      .DELETE('/api/v1/platform/tenants/{slug}/clients/{clientId}', {
        params: { path: { slug, clientId } },
      })
      .then(
        () => {
          notifications.show({ color: 'green', message: `Cliente ${clientId} revogado.` });
          request.reload();
        },
        (error: unknown) => {
          notifyError(error, 'Não foi possível revogar');
        },
      );
  };

  return (
    <Stack>
      <Group justify="flex-end">
        <Button
          onClick={() => {
            setCreating(true);
          }}
        >
          Novo cliente
        </Button>
      </Group>
      {request.status === 'error' ? (
        <Alert color="red" title="Não foi possível carregar os clientes">
          {request.error}
        </Alert>
      ) : request.status === 'loading' ? (
        <Text c="dimmed">Carregando…</Text>
      ) : (request.data ?? []).length === 0 ? (
        <Text c="dimmed">Nenhum cliente cadastrado.</Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Cliente</Table.Th>
              <Table.Th>Ambiente</Table.Th>
              <Table.Th>Integrações</Table.Th>
              <Table.Th>Situação</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(request.data ?? []).map((client) => (
              <Table.Tr key={client.clientId}>
                <Table.Td>{client.clientId}</Table.Td>
                <Table.Td>
                  <Badge variant="outline">{client.environment}</Badge>
                </Table.Td>
                <Table.Td>
                  {client.scopes.map((s) => s.replace('integration:execute:', '')).join(', ')}
                </Table.Td>
                <Table.Td>
                  <Badge color={client.status === 'ACTIVE' ? 'green' : 'red'}>
                    {client.status}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  {client.status === 'ACTIVE' && (
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label={`Revogar ${client.clientId}`}
                      onClick={() => {
                        revoke(client.clientId);
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
      )}
      {creating && (
        <NewClient
          slug={slug}
          onClose={() => {
            setCreating(false);
          }}
          onCreated={request.reload}
        />
      )}
    </Stack>
  );
}
