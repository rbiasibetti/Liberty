import { AppShell, Burger, Button, Group, Menu, NavLink, Select, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconListDetails, IconLogout, IconUser } from '@tabler/icons-react';
import { Link, Outlet, useLocation } from 'react-router';
import { useAuth } from '../auth/useAuth';
import { notifyError } from '../errors/notify';
import { useTenant } from '../tenant/tenantContext';

/** Casca da aplicação: cabeçalho com troca de tenant e saída, menu lateral e área de conteúdo. */
export function AppLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { user, logout } = useAuth();
  const tenant = useTenant();
  const location = useLocation();
  const name = user?.profile.preferred_username ?? user?.profile.name ?? user?.profile.sub ?? '';

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Menu" />
            <Title order={4}>Liberty</Title>
          </Group>
          <Group wrap="nowrap">
            <Select
              aria-label="Tenant"
              w={200}
              data={tenant.tenants}
              value={tenant.current}
              onChange={(slug) => {
                if (slug) {
                  tenant.select(slug);
                }
              }}
              allowDeselect={false}
              disabled={tenant.tenants.length === 0}
              placeholder="Sem tenant"
            />
            <Menu position="bottom-end">
              <Menu.Target>
                <Button variant="subtle" leftSection={<IconUser size={16} />}>
                  {name}
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item
                  leftSection={<IconLogout size={16} />}
                  onClick={() => {
                    logout().catch((error: unknown) => {
                      notifyError(error, 'Não foi possível sair');
                    });
                  }}
                >
                  Sair
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <NavLink
          component={Link}
          to="/"
          label="Integrações"
          leftSection={<IconListDetails size={16} />}
          active={location.pathname === '/'}
        />
        {tenant.platformAdmin && (
          <Text size="xs" c="dimmed" p="xs">
            Administrador da plataforma
          </Text>
        )}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
