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
import { notifications } from '@mantine/notifications';
import { useCallback, useState } from 'react';
import { Link } from 'react-router';
import { useApi } from '../api/apiContext';
import { useRequest } from '../api/useRequest';
import type { components } from '../api/schema';
import { notifyError } from '../errors/notify';
import { useTenant } from '../tenant/tenantContext';

type Tenant = components['schemas']['Tenant'];
type Status = Tenant['status'];

const STATUS_COLOR: Record<Status, string> = {
  ACTIVE: 'green',
  SUSPENDED: 'yellow',
  ARCHIVED: 'gray',
};
const CHECK_COLOR: Record<string, string> = {
  OK: 'green',
  PENDING: 'blue',
  WARN: 'yellow',
  FAIL: 'red',
};

function NewTenant({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const api = useApi();
  const [slug, setSlug] = useState('');
  const [name, setName] = useState('');
  const [plan, setPlan] = useState('STANDARD');
  const [busy, setBusy] = useState(false);

  const create = () => {
    setBusy(true);
    api.POST('/api/v1/platform/tenants', { body: { slug, name, planCode: plan } }).then(
      () => {
        notifications.show({ color: 'green', message: `Tenant ${slug} criado.` });
        onCreated();
        onClose();
      },
      (error: unknown) => {
        setBusy(false);
        notifyError(error, 'O tenant não foi criado');
      },
    );
  };

  return (
    <Modal opened onClose={onClose} title="Novo tenant">
      <Stack>
        <TextInput
          label="Identificador (slug)"
          description="2 a 63 letras minúsculas, números e hífen; vai nas rotas do cliente"
          value={slug}
          onChange={(e) => {
            setSlug(e.currentTarget.value);
          }}
        />
        <TextInput
          label="Nome"
          value={name}
          onChange={(e) => {
            setName(e.currentTarget.value);
          }}
        />
        <TextInput
          label="Plano"
          description="Define os limites de tráfego; STANDARD é o padrão"
          value={plan}
          onChange={(e) => {
            setPlan(e.currentTarget.value);
          }}
        />
        <Button onClick={create} loading={busy} disabled={!slug || !name.trim()}>
          Criar tenant
        </Button>
      </Stack>
    </Modal>
  );
}

function EditTenant({
  tenant,
  onClose,
  onSaved,
}: {
  tenant: Tenant;
  onClose: () => void;
  onSaved: () => void;
}) {
  const api = useApi();
  const [name, setName] = useState(tenant.name);
  const [plan, setPlan] = useState(tenant.planCode);
  const [status, setStatus] = useState<string | null>(tenant.status);
  const [busy, setBusy] = useState(false);

  const save = () => {
    setBusy(true);
    api
      .PATCH('/api/v1/platform/tenants/{slug}', {
        params: { path: { slug: tenant.slug } },
        body: { name, planCode: plan, status: (status ?? tenant.status) as Status },
      })
      .then(
        () => {
          notifications.show({ color: 'green', message: `Tenant ${tenant.slug} atualizado.` });
          onSaved();
          onClose();
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'O tenant não foi atualizado');
        },
      );
  };

  return (
    <Modal opened onClose={onClose} title={`Editar ${tenant.slug}`}>
      <Stack>
        <TextInput
          label="Nome"
          value={name}
          onChange={(e) => {
            setName(e.currentTarget.value);
          }}
        />
        <TextInput
          label="Plano"
          value={plan}
          onChange={(e) => {
            setPlan(e.currentTarget.value);
          }}
        />
        <Select
          label="Estado"
          description="Suspender corta usuários e clientes na hora; arquivar é definitivo"
          data={['ACTIVE', 'SUSPENDED', 'ARCHIVED']}
          value={status}
          onChange={setStatus}
          allowDeselect={false}
        />
        {status === 'ARCHIVED' && tenant.status !== 'ARCHIVED' && (
          <Alert color="red" title="Arquivar não tem volta">
            Um tenant arquivado deixa de funcionar e não pode ser reativado nem alterado.
          </Alert>
        )}
        <Button onClick={save} loading={busy} disabled={!name.trim() || !plan.trim()}>
          Salvar
        </Button>
      </Stack>
    </Modal>
  );
}

function Onboarding({ slug, onClose }: { slug: string; onClose: () => void }) {
  const api = useApi();
  const fetchOne = useCallback(async () => {
    const { data } = await api.GET('/api/v1/platform/tenants/{slug}/onboarding', {
      params: { path: { slug } },
    });
    return data ?? null;
  }, [api, slug]);
  const request = useRequest(`onboarding:${slug}`, fetchOne);

  return (
    <Modal opened onClose={onClose} title={`Onboarding de ${slug}`} size="lg">
      {request.status === 'loading' ? (
        <Group>
          <Loader size="sm" aria-label="Conferindo o tenant" />
          <Text c="dimmed">Conferindo conexões e privilégios…</Text>
        </Group>
      ) : request.status === 'error' ? (
        <Alert color="red" title="Não foi possível conferir">
          {request.error}
        </Alert>
      ) : request.data ? (
        <Stack>
          <Alert
            color={request.data.ready ? 'green' : 'blue'}
            title={request.data.ready ? 'Pronto para uso' : 'Ainda falta configurar'}
          >
            {request.data.ready
              ? 'Nada pendente. Avisos não bloqueiam, mas vale resolvê-los.'
              : 'Resolva os itens pendentes ou com falha abaixo.'}
          </Alert>
          <Table>
            <Table.Tbody>
              {request.data.checks.map((check) => (
                <Table.Tr key={check.id}>
                  <Table.Td w={110}>
                    <Badge color={CHECK_COLOR[check.status] ?? 'gray'}>{check.status}</Badge>
                  </Table.Td>
                  <Table.Td>
                    <Text fw={600} size="sm">
                      {check.label}
                    </Text>
                    <Text size="sm" c="dimmed">
                      {check.detail}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      ) : null}
    </Modal>
  );
}

/** Tenants da plataforma: criar, mudar plano e estado, conferir o onboarding; só o administrador da plataforma. */
export function PlatformTenantsPage() {
  const { platformAdmin } = useTenant();
  const api = useApi();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Tenant | null>(null);
  const [checking, setChecking] = useState<string | null>(null);
  const fetchAll = useCallback(async () => {
    const { data } = await api.GET('/api/v1/platform/tenants');
    return data ?? [];
  }, [api]);
  const request = useRequest('platform-tenants', fetchAll);

  if (!platformAdmin) {
    return (
      <Alert color="yellow" title="Acesso restrito">
        Os tenants são do administrador da plataforma.
      </Alert>
    );
  }
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Tenants da plataforma</Title>
        <Button
          onClick={() => {
            setCreating(true);
          }}
        >
          Novo tenant
        </Button>
      </Group>
      {request.status === 'error' ? (
        <Alert color="red" title="Não foi possível carregar os tenants">
          {request.error}
        </Alert>
      ) : request.status === 'loading' ? (
        <Loader aria-label="Carregando tenants" />
      ) : (request.data ?? []).length === 0 ? (
        <Text c="dimmed">Nenhum tenant cadastrado.</Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Identificador</Table.Th>
              <Table.Th>Nome</Table.Th>
              <Table.Th>Plano</Table.Th>
              <Table.Th>Estado</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(request.data ?? []).map((tenant) => (
              <Table.Tr key={tenant.slug}>
                <Table.Td>{tenant.slug}</Table.Td>
                <Table.Td>{tenant.name}</Table.Td>
                <Table.Td>{tenant.planCode}</Table.Td>
                <Table.Td>
                  <Badge color={STATUS_COLOR[tenant.status]}>{tenant.status}</Badge>
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end">
                    <Button
                      size="xs"
                      variant="default"
                      component={Link}
                      to={`/administracao?tenant=${tenant.slug}`}
                      aria-label={`Administrar ${tenant.slug}`}
                    >
                      Administrar
                    </Button>
                    <Button
                      size="xs"
                      variant="default"
                      aria-label={`Onboarding de ${tenant.slug}`}
                      onClick={() => {
                        setChecking(tenant.slug);
                      }}
                    >
                      Onboarding
                    </Button>
                    {tenant.status !== 'ARCHIVED' && (
                      <Button
                        size="xs"
                        variant="light"
                        aria-label={`Editar ${tenant.slug}`}
                        onClick={() => {
                          setEditing(tenant);
                        }}
                      >
                        Editar
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {creating && (
        <NewTenant
          onClose={() => {
            setCreating(false);
          }}
          onCreated={request.reload}
        />
      )}
      {editing && (
        <EditTenant
          tenant={editing}
          onClose={() => {
            setEditing(null);
          }}
          onSaved={request.reload}
        />
      )}
      {checking && (
        <Onboarding
          slug={checking}
          onClose={() => {
            setChecking(null);
          }}
        />
      )}
    </Stack>
  );
}
