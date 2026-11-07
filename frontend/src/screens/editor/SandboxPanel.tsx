import { Alert, Badge, Button, Code, Group, Stack, Text, Textarea, Title } from '@mantine/core';
import { useState } from 'react';
import { useApi } from '../../api/apiContext';
import type { components } from '../../api/schema';
import { notifyError } from '../../errors/notify';

type SandboxResult = components['schemas']['SandboxResult'];

interface Props {
  slug: string;
  integrationKey: string;
  /** Versão salva a executar; nula quando há alterações não salvas. */
  versionNo: number | null;
  /** Escritas pedem corpo; leituras só parâmetros. */
  hasBody: boolean;
}

function parseObject(text: string): Record<string, unknown> | null | undefined {
  if (text.trim() === '') {
    return {};
  }
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

/** Painel do sandbox: roda a versão salva em HML com a transação desfeita e mostra o resultado, o SQL e os logs. */
export function SandboxPanel({ slug, integrationKey, versionNo, hasBody }: Props) {
  const api = useApi();
  const [params, setParams] = useState('{}');
  const [body, setBody] = useState('{}');
  const [result, setResult] = useState<SandboxResult | null>(null);
  const [running, setRunning] = useState(false);
  const invalid = parseObject(params) === undefined || (hasBody && parseObject(body) === undefined);

  const run = () => {
    if (versionNo === null) {
      return;
    }
    setRunning(true);
    api
      .POST('/api/v1/tenants/{slug}/integrations/{key}/versions/{no}/sandbox', {
        params: { path: { slug, key: integrationKey, no: versionNo } },
        body: { params: parseObject(params) ?? {}, body: hasBody ? parseObject(body) : null },
      })
      .then(
        ({ data }) => {
          setResult(data ?? null);
          setRunning(false);
        },
        (error: unknown) => {
          setRunning(false);
          notifyError(error, 'O sandbox não respondeu');
        },
      );
  };

  return (
    <Stack p="sm" gap="xs">
      <Title order={5}>Sandbox (HML, nada é gravado)</Title>
      {versionNo === null && <Alert color="yellow">Salve o rascunho para testar.</Alert>}
      <Textarea
        label="Parâmetros (JSON)"
        autosize
        minRows={2}
        value={params}
        error={parseObject(params) === undefined ? 'JSON inválido' : undefined}
        onChange={(e) => {
          setParams(e.currentTarget.value);
        }}
      />
      {hasBody && (
        <Textarea
          label="Corpo (JSON)"
          autosize
          minRows={4}
          value={body}
          error={parseObject(body) === undefined ? 'JSON inválido' : undefined}
          onChange={(e) => {
            setBody(e.currentTarget.value);
          }}
        />
      )}
      <Button onClick={run} loading={running} disabled={versionNo === null || invalid}>
        Executar
      </Button>
      {result && (
        <Stack gap="xs">
          <Group>
            <Badge color={result.succeeded ? 'green' : 'red'}>
              {result.succeeded ? 'sucesso' : 'falha'}
            </Badge>
            <Text size="sm">{`status ${String(result.status)} · ${String(result.millis)} ms · desfeito`}</Text>
          </Group>
          {result.problem && (
            <Alert color="red" title={result.problem.type}>
              {result.problem.detail}
              {result.problem.violations.map((v, i) => (
                <Text key={i} size="xs">
                  {[v.parameter, v.message].filter(Boolean).join(': ')}
                </Text>
              ))}
            </Alert>
          )}
          {result.body !== undefined && result.body !== null && (
            <Code block>{JSON.stringify(result.body, null, 2)}</Code>
          )}
          <Text size="sm" fw={600}>
            SQL executado ({String(result.sql.length)})
          </Text>
          {result.sql.map((entry, i) => (
            <Code key={i} block>
              {`${entry.sql}\n-- ${String(entry.rows)} linha(s), ${String(entry.millis)} ms`}
            </Code>
          ))}
          {result.logs && (
            <>
              <Text size="sm" fw={600}>
                Console
              </Text>
              <Code block>{result.logs}</Code>
            </>
          )}
        </Stack>
      )}
    </Stack>
  );
}
