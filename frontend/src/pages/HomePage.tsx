import { Alert, Badge, Button, Group, Loader, Stack, Table, Text, Title } from '@mantine/core';
import { useCallback } from 'react';
import { useApi } from '../api/apiContext';
import { useRequest } from '../api/useRequest';
import { useTenant } from '../tenant/tenantContext';

/** Integrações do tenant atual (as telas de edição chegam na E20). */
export function HomePage() {
  const api = useApi();
  const { current } = useTenant();
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
        <Button variant="default" onClick={request.reload}>
          Atualizar
        </Button>
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
              <Table.Th>Nome</Table.Th>
              <Table.Th>Método</Table.Th>
              <Table.Th>Rota</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {items.map((item) => (
              <Table.Tr key={item.key}>
                <Table.Td>{item.key}</Table.Td>
                <Table.Td>{item.name}</Table.Td>
                <Table.Td>
                  <Badge variant="light">{item.method}</Badge>
                </Table.Td>
                <Table.Td>
                  <code>{item.route}</code>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
