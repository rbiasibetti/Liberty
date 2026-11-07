import {
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useCallback, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useApi } from '../api/apiContext';
import { useRequest } from '../api/useRequest';
import { useAuth } from '../auth/useAuth';
import { DiffViewer } from '../editor/CodeEditor';
import { notifyError } from '../errors/notify';
import { diffSnapshots } from '../snapshot/diff';
import type { Snapshot } from '../snapshot/model';
import { runTests } from '../snapshot/testRunner';
import type { TestResult } from '../snapshot/testRunner';
import { useTenant } from '../tenant/tenantContext';

function short(value: unknown): string {
  const text = value === undefined ? '' : JSON.stringify(value);
  return text.length > 80 ? `${text.slice(0, 80)}…` : text;
}

interface Loaded {
  versions: { versionNo: number }[];
  active: { HML: number | null; PRD: number | null };
}

/** Versão contra a qual comparar: a escolhida, senão a ativa em HML, senão a anterior. */
function pickBase(chosen: string | null, data: Loaded, versionNo: number): number | null {
  if (chosen !== null) {
    return chosen === 'none' ? null : Number(chosen);
  }
  const hml = data.active.HML;
  if (hml !== null && hml !== versionNo) {
    return hml;
  }
  return data.versions.some((v) => v.versionNo === versionNo - 1) ? versionNo - 1 : null;
}

const STATE_COLOR: Record<string, string> = {
  DRAFT: 'gray',
  IN_REVIEW: 'yellow',
  PUBLISHED: 'green',
  REJECTED: 'red',
};

/** Tela 4: revisão e publicação, com o diff da versão, o resultado dos testes e as ações por ambiente. */
export function ReviewPage() {
  const { key = '', no = '' } = useParams();
  const versionNo = Number(no);
  const api = useApi();
  const tenant = useTenant();
  const { user } = useAuth();
  const slug = tenant.current ?? '';
  const [comment, setComment] = useState('');
  const [compareWith, setCompareWith] = useState<string | null>(null);
  const [results, setResults] = useState<TestResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const path = { slug, key };
    const [{ data: versions }, { data: integrations }] = await Promise.all([
      api.GET('/api/v1/tenants/{slug}/integrations/{key}/versions', { params: { path } }),
      api.GET('/api/v1/tenants/{slug}/integrations', { params: { path: { slug } } }),
    ]);
    const version = (versions ?? []).find((v) => v.versionNo === versionNo);
    if (!version) {
      throw new Error('Versão não encontrada');
    }
    const meta = (integrations ?? []).find((i) => i.key === key);
    const { data: snapshot } = await api.GET(
      '/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/snapshot',
      { params: { path: { ...path, no: versionNo } } },
    );
    return {
      version,
      versions: versions ?? [],
      active: { HML: meta?.hmlVersionNo ?? null, PRD: meta?.prdVersionNo ?? null },
      snapshot: snapshot as unknown as Snapshot,
    };
  }, [api, slug, key, versionNo]);
  const request = useRequest(`review:${slug}:${key}:${no}`, load);

  const baseKey = request.data ? (pickBase(compareWith, request.data, versionNo) ?? 0) : 0;
  const loadBase = useCallback(async () => {
    if (!baseKey) {
      return null;
    }
    const { data } = await api.GET(
      '/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/snapshot',
      { params: { path: { slug, key, no: baseKey } } },
    );
    return data as unknown as Snapshot;
  }, [api, slug, key, baseKey]);
  const base = useRequest(`review-base:${slug}:${key}:${String(baseKey)}`, loadBase);

  if (request.status === 'loading') {
    return <Loader aria-label="Carregando a versão" />;
  }
  if (request.status === 'error' || !request.data) {
    return (
      <Alert color="red" title="Não foi possível abrir a versão">
        {request.error ?? 'Versão não encontrada.'}
      </Alert>
    );
  }
  const { version, snapshot, active, versions } = request.data;
  const canPublish = tenant.can('PUBLISHER');
  const isAuthor = version.author === user?.profile.sub;
  const diff = base.data ? diffSnapshots(base.data, snapshot) : null;

  const act = (label: string, call: () => Promise<unknown>) => {
    setBusy(true);
    call().then(
      () => {
        notifications.show({ color: 'green', message: label });
        setBusy(false);
        setComment('');
        request.reload();
      },
      (error: unknown) => {
        setBusy(false);
        notifyError(error, 'A ação não foi concluída');
      },
    );
  };
  const path = { slug, key, no: versionNo };
  const publish = (env: 'HML' | 'PRD') => {
    act(`Publicada em ${env}.`, () =>
      api.POST('/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/publish/{env}', {
        params: { path: { ...path, env } },
        body: { comment },
      }),
    );
  };
  const rollback = (env: 'HML' | 'PRD') => {
    act(`Rollback feito em ${env}.`, () =>
      api.POST('/api/v1/tenants/{slug}/integrations/{key}/environments/{env}/rollback', {
        params: { path: { slug, key, env } },
      }),
    );
  };
  const unpublish = (env: 'HML' | 'PRD') => {
    act(`Despublicada de ${env}.`, () =>
      api.DELETE('/api/v1/tenants/{slug}/integrations/{key}/environments/{env}/publication', {
        params: { path: { slug, key, env } },
      }),
    );
  };

  return (
    <Stack>
      <Group justify="space-between">
        <Group>
          <Title order={2}>{`${key} · v${String(version.versionNo)}`}</Title>
          <Badge color={STATE_COLOR[version.state] ?? 'gray'}>{version.state}</Badge>
          <Text c="dimmed" size="sm">
            {`autor ${version.author}${version.reviewer ? ` · revisor ${version.reviewer}` : ''}`}
          </Text>
        </Group>
        <Button variant="default" component={Link} to={`/integracoes/${key}`}>
          Voltar ao editor
        </Button>
      </Group>
      {version.comment && <Alert title="Comentário da revisão">{version.comment}</Alert>}
      <Group>
        <Text size="sm">{`Ativa em HML: ${active.HML ? `v${String(active.HML)}` : '—'} · PRD: ${active.PRD ? `v${String(active.PRD)}` : '—'}`}</Text>
      </Group>

      <Group align="flex-end">
        <Select
          label="Comparar com"
          w={260}
          allowDeselect={false}
          data={[
            { value: 'none', label: 'Nada (versão inteira)' },
            ...versions
              .filter((v) => v.versionNo !== versionNo)
              .map((v) => ({
                value: String(v.versionNo),
                label: `v${String(v.versionNo)} (${v.state})`,
              })),
          ]}
          value={baseKey ? String(baseKey) : 'none'}
          onChange={setCompareWith}
        />
      </Group>
      <Title order={4}>Mudanças</Title>
      {diff === null ? (
        <Text c="dimmed">Sem versão de comparação: nada a comparar.</Text>
      ) : diff.changes.length === 0 ? (
        <Text c="dimmed">Nenhuma mudança além dos scripts.</Text>
      ) : (
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Onde</Table.Th>
              <Table.Th>Mudança</Table.Th>
              <Table.Th>Antes</Table.Th>
              <Table.Th>Depois</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {diff.changes.map((change) => (
              <Table.Tr key={`${change.path}-${change.kind}`}>
                <Table.Td>
                  <code>{change.path}</code>
                </Table.Td>
                <Table.Td>
                  <Badge
                    color={
                      change.kind === 'added'
                        ? 'green'
                        : change.kind === 'removed'
                          ? 'red'
                          : 'yellow'
                    }
                  >
                    {{ added: 'novo', removed: 'removido', changed: 'alterado' }[change.kind]}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Code>{short(change.before)}</Code>
                </Table.Td>
                <Table.Td>
                  <Code>{short(change.after)}</Code>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {diff &&
        (['beforeExecute', 'afterExecute'] as const).map((name) => {
          const [before, after] = diff.scripts[name];
          return before === after ? null : (
            <Stack key={name} gap="xs">
              <Title order={5}>{`Script ${name}`}</Title>
              <DiffViewer original={before} modified={after} language="javascript" />
            </Stack>
          );
        })}

      <Title order={4}>Testes</Title>
      <Group>
        <Button
          variant="default"
          disabled={(snapshot.tests ?? []).length === 0}
          onClick={() => {
            void runTests(api, slug, key, versionNo, snapshot.tests ?? []).then(setResults);
          }}
        >
          {`Executar os ${String((snapshot.tests ?? []).length)} testes`}
        </Button>
        {(snapshot.tests ?? []).length === 0 && (
          <Text c="dimmed">Esta versão não tem casos de teste.</Text>
        )}
      </Group>
      {results && (
        <Alert
          color={results.every((r) => r.passed) ? 'green' : 'red'}
          title="Resultado dos testes"
        >
          {results.map((r) => (
            <Text key={r.name} size="sm">
              {r.passed ? '✔' : '✘'} {r.name}
              {r.reason ? ` — ${r.reason}` : ''}
            </Text>
          ))}
        </Alert>
      )}

      {canPublish && (
        <Stack>
          <Title order={4}>Decisão</Title>
          {isAuthor && version.state !== 'PUBLISHED' && (
            <Alert color="yellow">
              Você escreveu esta versão: quem a escreve não a aprova nem a publica.
            </Alert>
          )}
          {version.state === 'IN_REVIEW' && (
            <>
              <Textarea
                label="Comentário"
                autosize
                minRows={2}
                value={comment}
                onChange={(e) => {
                  setComment(e.currentTarget.value);
                }}
              />
              <Group>
                <Button
                  loading={busy}
                  disabled={isAuthor}
                  onClick={() => {
                    publish('HML');
                  }}
                >
                  Aprovar e publicar em HML
                </Button>
                <Button
                  color="red"
                  variant="light"
                  loading={busy}
                  disabled={isAuthor}
                  onClick={() => {
                    act('Versão rejeitada.', () =>
                      api.POST('/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/reject', {
                        params: { path },
                        body: { comment },
                      }),
                    );
                  }}
                >
                  Rejeitar
                </Button>
              </Group>
            </>
          )}
          {version.state === 'PUBLISHED' && (
            <Stack>
              <Group>
                <Button
                  loading={busy}
                  disabled={isAuthor || active.HML === versionNo}
                  onClick={() => {
                    publish('HML');
                  }}
                >
                  Publicar em HML
                </Button>
                <Button
                  loading={busy}
                  disabled={isAuthor || active.PRD === versionNo}
                  onClick={() => {
                    publish('PRD');
                  }}
                >
                  Promover para PRD
                </Button>
              </Group>
              <Group>
                {(['HML', 'PRD'] as const).map((env) => (
                  <Group key={env} gap="xs">
                    <Button
                      variant="light"
                      color="orange"
                      loading={busy}
                      disabled={active[env] === null}
                      onClick={() => {
                        rollback(env);
                      }}
                    >{`Rollback em ${env}`}</Button>
                    <Button
                      variant="subtle"
                      color="red"
                      loading={busy}
                      disabled={active[env] === null}
                      onClick={() => {
                        unpublish(env);
                      }}
                    >{`Despublicar de ${env}`}</Button>
                  </Group>
                ))}
              </Group>
            </Stack>
          )}
          {(version.state === 'DRAFT' || version.state === 'REJECTED') && (
            <Text c="dimmed">Esta versão não está em revisão.</Text>
          )}
        </Stack>
      )}
    </Stack>
  );
}
