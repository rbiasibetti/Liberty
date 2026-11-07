import {
  Alert,
  Badge,
  Button,
  CloseButton,
  Group,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useCallback, useState } from 'react';
import { useApi } from '../../api/apiContext';
import { useRequest } from '../../api/useRequest';
import { notifyError } from '../../errors/notify';

type Role = 'VIEWER' | 'INTEGRATOR' | 'PUBLISHER';

/** Usuários do tenant e seus papéis: conceder e retirar (vale na hora, o servidor lê a cada chamada). */
export function UsersTab({ slug }: { slug: string }) {
  const api = useApi();
  const [sub, setSub] = useState('');
  const [role, setRole] = useState<string | null>('VIEWER');
  const fetchAll = useCallback(async () => {
    const { data } = await api.GET('/api/v1/platform/tenants/{slug}/users', {
      params: { path: { slug } },
    });
    return data ?? [];
  }, [api, slug]);
  const request = useRequest(`users:${slug}`, fetchAll);

  const grant = () => {
    api
      .PUT('/api/v1/platform/tenants/{slug}/users/{sub}/roles/{role}', {
        params: { path: { slug, sub: sub.trim(), role: (role ?? 'VIEWER') as Role } },
      })
      .then(
        () => {
          notifications.show({ color: 'green', message: 'Papel concedido.' });
          setSub('');
          request.reload();
        },
        (error: unknown) => {
          notifyError(error, 'Não foi possível conceder o papel');
        },
      );
  };
  const revoke = (user: string, userRole: Role) => {
    api
      .DELETE('/api/v1/platform/tenants/{slug}/users/{sub}/roles/{role}', {
        params: { path: { slug, sub: user, role: userRole } },
      })
      .then(
        () => {
          request.reload();
        },
        (error: unknown) => {
          notifyError(error, 'Não foi possível retirar o papel');
        },
      );
  };

  return (
    <Stack>
      <Group align="flex-end">
        <TextInput
          label="Usuário (sub do provedor)"
          value={sub}
          onChange={(e) => {
            setSub(e.currentTarget.value);
          }}
        />
        <Select
          label="Papel"
          w={160}
          data={['VIEWER', 'INTEGRATOR', 'PUBLISHER']}
          value={role}
          onChange={setRole}
          allowDeselect={false}
        />
        <Button onClick={grant} disabled={sub.trim() === ''}>
          Conceder
        </Button>
      </Group>
      {request.status === 'error' ? (
        <Alert color="red" title="Não foi possível carregar os usuários">
          {request.error}
        </Alert>
      ) : request.status === 'loading' ? (
        <Text c="dimmed">Carregando…</Text>
      ) : (request.data ?? []).length === 0 ? (
        <Text c="dimmed">Nenhum usuário com papel neste tenant.</Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Usuário</Table.Th>
              <Table.Th>Papéis</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(request.data ?? []).map((user) => (
              <Table.Tr key={user.sub}>
                <Table.Td>{user.sub}</Table.Td>
                <Table.Td>
                  <Group gap="xs">
                    {user.roles.map((r) => (
                      <Badge
                        key={r}
                        rightSection={
                          <CloseButton
                            size="xs"
                            aria-label={`Retirar ${r} de ${user.sub}`}
                            onClick={() => {
                              revoke(user.sub, r);
                            }}
                          />
                        }
                      >
                        {r}
                      </Badge>
                    ))}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
