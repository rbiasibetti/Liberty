import { Alert, Stack, Tabs, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useTenant } from '../tenant/tenantContext';
import { ClientsTab } from './admin/ClientsTab';
import { ConnectionsTab } from './admin/ConnectionsTab';
import { UsersTab } from './admin/UsersTab';

/** Tela 6: administração de um tenant (conexões, clientes OAuth2, usuários e papéis), só para o administrador da plataforma. */
export function TenantAdminPage() {
  const { platformAdmin, current } = useTenant();
  const [typed, setTyped] = useState(current ?? '');
  const [slug, setSlug] = useState(current ?? '');

  if (!platformAdmin) {
    return (
      <Alert color="yellow" title="Acesso restrito">
        A administração de tenants é do administrador da plataforma.
      </Alert>
    );
  }
  return (
    <Stack>
      <Title order={2}>Administração do tenant</Title>
      <TextInput
        label="Tenant"
        description="Slug do tenant a administrar (Enter para abrir)"
        value={typed}
        w={320}
        onChange={(e) => {
          setTyped(e.currentTarget.value);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            setSlug(typed.trim());
          }
        }}
      />
      {slug === '' ? (
        <Text c="dimmed">Informe o tenant para começar.</Text>
      ) : (
        <Tabs defaultValue="conexoes" keepMounted={false} key={slug}>
          <Tabs.List>
            <Tabs.Tab value="conexoes">Conexões</Tabs.Tab>
            <Tabs.Tab value="clientes">Clientes OAuth2</Tabs.Tab>
            <Tabs.Tab value="usuarios">Usuários e papéis</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="conexoes" pt="md">
            <ConnectionsTab slug={slug} />
          </Tabs.Panel>
          <Tabs.Panel value="clientes" pt="md">
            <ClientsTab slug={slug} />
          </Tabs.Panel>
          <Tabs.Panel value="usuarios" pt="md">
            <UsersTab slug={slug} />
          </Tabs.Panel>
        </Tabs>
      )}
    </Stack>
  );
}
