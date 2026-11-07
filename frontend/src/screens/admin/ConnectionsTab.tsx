import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  NumberInput,
  PasswordInput,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useCallback, useState } from 'react';
import { useApi } from '../../api/apiContext';
import type { components } from '../../api/schema';
import { useRequest } from '../../api/useRequest';
import { notifyError } from '../../errors/notify';

type Connection = components['schemas']['Connection'];
type Env = 'HML' | 'PRD';

function EnvironmentForm({
  slug,
  env,
  saved,
  onSaved,
}: {
  slug: string;
  env: Env;
  saved: Connection | undefined;
  onSaved: () => void;
}) {
  const api = useApi();
  const [jdbcUrl, setJdbcUrl] = useState(saved?.jdbcUrl ?? '');
  const [dbUsername, setDbUsername] = useState(saved?.dbUsername ?? '');
  const [poolMax, setPoolMax] = useState<number | string>(saved?.poolMax ?? 5);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [test, setTest] = useState<components['schemas']['ConnectionTest'] | null>(null);

  const submit = () => {
    setBusy(true);
    api
      .PUT('/api/v1/platform/tenants/{slug}/connections/{env}', {
        params: { path: { slug, env } },
        body: {
          jdbcUrl,
          dbUsername,
          poolMax: typeof poolMax === 'number' ? poolMax : null,
          password: password === '' ? null : password,
        },
      })
      .then(
        () => {
          notifications.show({ color: 'green', message: `Conexão de ${env} salva.` });
          setPassword('');
          setBusy(false);
          onSaved();
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'A conexão não foi salva');
        },
      );
  };

  const runTest = () => {
    setBusy(true);
    api
      .POST('/api/v1/platform/tenants/{slug}/connections/{env}/test', {
        params: { path: { slug, env } },
      })
      .then(
        ({ data }) => {
          setTest(data ?? null);
          setBusy(false);
        },
        (error: unknown) => {
          setBusy(false);
          notifyError(error, 'O teste não pôde ser feito');
        },
      );
  };

  return (
    <Card withBorder>
      <Stack>
        <Group>
          <Text fw={600}>{env === 'HML' ? 'Homologação (HML)' : 'Produção (PRD)'}</Text>
          {saved ? (
            <Badge color="green">cadastrada</Badge>
          ) : (
            <Badge color="gray">não cadastrada</Badge>
          )}
        </Group>
        <TextInput
          label="URL JDBC"
          description="jdbc:sqlserver://host:1433;databaseName=...;encrypt=true"
          value={jdbcUrl}
          onChange={(e) => {
            setJdbcUrl(e.currentTarget.value);
          }}
        />
        <Group grow align="flex-start">
          <TextInput
            label="Usuário do banco"
            value={dbUsername}
            onChange={(e) => {
              setDbUsername(e.currentTarget.value);
            }}
          />
          <NumberInput
            label="Conexões no pool"
            min={1}
            max={20}
            value={poolMax}
            onChange={setPoolMax}
          />
        </Group>
        <PasswordInput
          label="Senha"
          description={
            saved
              ? 'Deixe em branco para manter a senha do cofre'
              : 'Vai direto para o cofre e nunca volta'
          }
          value={password}
          autoComplete="new-password"
          onChange={(e) => {
            setPassword(e.currentTarget.value);
          }}
        />
        <Group>
          <Button onClick={submit} loading={busy} disabled={!jdbcUrl || !dbUsername}>
            Salvar
          </Button>
          <Button variant="default" onClick={runTest} disabled={busy || !saved}>
            Testar conexão
          </Button>
        </Group>
        {test && (
          <Alert
            color={test.ok ? 'green' : 'red'}
            title={test.ok ? 'Conexão ok' : 'Falha na conexão'}
          >
            {`${test.message} (${String(test.millis)} ms)`}
          </Alert>
        )}
      </Stack>
    </Card>
  );
}

/** Conexões do tenant com os bancos de HML e PRD; a senha só é enviada, nunca exibida. */
export function ConnectionsTab({ slug }: { slug: string }) {
  const api = useApi();
  const fetchAll = useCallback(async () => {
    const { data } = await api.GET('/api/v1/platform/tenants/{slug}/connections', {
      params: { path: { slug } },
    });
    return data ?? [];
  }, [api, slug]);
  const request = useRequest(`connections:${slug}`, fetchAll);

  if (request.status === 'error') {
    return (
      <Alert color="red" title="Não foi possível carregar as conexões">
        {request.error}
      </Alert>
    );
  }
  if (request.status === 'loading') {
    return <Text c="dimmed">Carregando…</Text>;
  }
  const saved = request.data ?? [];
  return (
    <Stack>
      {(['HML', 'PRD'] as const).map((env) => (
        <EnvironmentForm
          key={`${env}:${saved.find((c) => c.environment === env)?.jdbcUrl ?? ''}`}
          slug={slug}
          env={env}
          saved={saved.find((c) => c.environment === env)}
          onSaved={request.reload}
        />
      ))}
    </Stack>
  );
}
