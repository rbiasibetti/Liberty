import { Alert, Badge, Button, Group, Loader, Stack, Tabs, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useCallback, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useApi } from '../api/apiContext';
import { useRequest } from '../api/useRequest';
import { notifyError } from '../errors/notify';
import { Split } from '../layout/Split';
import { blankSnapshot } from '../snapshot/model';
import type { Snapshot } from '../snapshot/model';
import { useTenant } from '../tenant/tenantContext';
import { MappingTab } from './editor/MappingTab';
import { RouteTab } from './editor/RouteTab';
import { SandboxPanel } from './editor/SandboxPanel';
import { SchemaTab } from './editor/SchemaTab';
import { ScriptsTab } from './editor/ScriptsTab';
import { TestsTab } from './editor/TestsTab';

/** Tela 2: editor de integração (rota, schema, mapeamento, scripts e testes) com o sandbox ao lado. */
export function EditorPage() {
  const { key = '' } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const tenant = useTenant();
  const slug = tenant.current ?? '';
  const readOnlyRole = !tenant.can('INTEGRATOR');
  const [edited, setEdited] = useState<Snapshot | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const path = { slug, key };
    const [{ data: versions }, { data: integrations }] = await Promise.all([
      api.GET('/api/v1/tenants/{slug}/integrations/{key}/versions', { params: { path } }),
      api.GET('/api/v1/tenants/{slug}/integrations', { params: { path: { slug } } }),
    ]);
    const list = versions ?? [];
    const chosen = list.find((v) => v.state === 'DRAFT') ?? list[0];
    if (chosen) {
      const { data } = await api.GET(
        '/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/snapshot',
        {
          params: { path: { ...path, no: chosen.versionNo } },
        },
      );
      return {
        snapshot: data as unknown as Snapshot,
        versionNo: chosen.versionNo,
        state: chosen.state,
      };
    }
    const meta = (integrations ?? []).find((i) => i.key === key);
    if (!meta) {
      throw new Error('Integração não encontrada');
    }
    return {
      snapshot: blankSnapshot({ key, name: meta.name, method: meta.method, route: meta.route }),
      versionNo: null,
      state: null as string | null,
    };
  }, [api, slug, key]);
  const request = useRequest(`editor:${slug}:${key}`, load);

  if (request.status === 'loading') {
    return <Loader aria-label="Carregando a integração" />;
  }
  if (request.status === 'error' || !request.data) {
    return (
      <Alert color="red" title="Não foi possível abrir a integração">
        {request.error ?? 'Integração não encontrada.'}
      </Alert>
    );
  }
  const base = request.data;
  const snapshot = edited ?? base.snapshot;
  const dirty = edited !== null;
  const isDraft = base.state === 'DRAFT';
  const readOnly = readOnlyRole;
  const savedVersion = dirty ? null : base.versionNo;
  const needsSave = dirty || base.versionNo === null;
  const edit = (next: Snapshot) => {
    setEdited(next);
  };

  const save = () => {
    setBusy(true);
    api
      .PUT('/api/v1/tenants/{slug}/integrations/{key}/draft', {
        params: { path: { slug, key } },
        body: snapshot as unknown as Record<string, unknown>,
      })
      .then(
        () => {
          notifications.show({ color: 'green', message: 'Rascunho salvo.' });
          setEdited(null);
          setBusy(false);
          request.reload();
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'O rascunho não foi salvo');
        },
      );
  };

  const submit = () => {
    if (base.versionNo === null) {
      return;
    }
    setBusy(true);
    api
      .POST('/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/submit', {
        params: { path: { slug, key, no: base.versionNo } },
      })
      .then(
        () => {
          void navigate(`/integracoes/${key}/versoes/${String(base.versionNo)}`);
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'Não foi possível enviar para revisão');
        },
      );
  };

  const main = (
    <Tabs defaultValue="rota" keepMounted={false}>
      <Tabs.List>
        <Tabs.Tab value="rota">Rota e filtros</Tabs.Tab>
        <Tabs.Tab value="schema">JSON Schema</Tabs.Tab>
        <Tabs.Tab value="mapeamento">Mapeamento</Tabs.Tab>
        <Tabs.Tab value="scripts">Scripts</Tabs.Tab>
        <Tabs.Tab value="testes">Testes</Tabs.Tab>
      </Tabs.List>
      <Tabs.Panel value="rota" pt="md">
        <RouteTab slug={slug} snapshot={snapshot} readOnly={readOnly} onChange={edit} />
      </Tabs.Panel>
      <Tabs.Panel value="schema" pt="md">
        <SchemaTab snapshot={snapshot} readOnly={readOnly} onChange={edit} />
      </Tabs.Panel>
      <Tabs.Panel value="mapeamento" pt="md">
        <MappingTab slug={slug} snapshot={snapshot} readOnly={readOnly} onChange={edit} />
      </Tabs.Panel>
      <Tabs.Panel value="scripts" pt="md">
        <ScriptsTab snapshot={snapshot} readOnly={readOnly} onChange={edit} />
      </Tabs.Panel>
      <Tabs.Panel value="testes" pt="md">
        <TestsTab
          slug={slug}
          integrationKey={key}
          versionNo={savedVersion}
          snapshot={snapshot}
          readOnly={readOnly}
          onChange={edit}
        />
      </Tabs.Panel>
    </Tabs>
  );

  return (
    <Stack>
      <Group justify="space-between">
        <Group>
          <Title order={2}>{snapshot.integration.name || key}</Title>
          <Badge variant="outline">{snapshot.integration.method}</Badge>
          <Text c="dimmed">
            <code>{snapshot.integration.route}</code>
          </Text>
          {base.versionNo !== null && <Badge>{`v${String(base.versionNo)} · ${base.state}`}</Badge>}
          {dirty && <Badge color="yellow">alterações não salvas</Badge>}
        </Group>
        <Group>
          <Button variant="default" component={Link} to="/">
            Voltar
          </Button>
          {base.versionNo !== null && !isDraft && (
            <Button
              variant="default"
              component={Link}
              to={`/integracoes/${key}/versoes/${String(base.versionNo)}`}
            >
              Ver revisão
            </Button>
          )}
          {!readOnlyRole && (
            <>
              <Button onClick={save} loading={busy} disabled={!needsSave}>
                {isDraft || base.versionNo === null ? 'Salvar rascunho' : 'Salvar como nova versão'}
              </Button>
              <Button variant="light" onClick={submit} disabled={busy || dirty || !isDraft}>
                Enviar para revisão
              </Button>
            </>
          )}
        </Group>
      </Group>
      <Split
        main={main}
        side={
          <SandboxPanel
            slug={slug}
            integrationKey={key}
            versionNo={savedVersion}
            hasBody={snapshot.operation.type !== 'SELECT' && snapshot.operation.type !== 'DELETE'}
          />
        }
      />
    </Stack>
  );
}
