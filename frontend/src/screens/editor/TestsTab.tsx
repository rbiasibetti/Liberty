import {
  ActionIcon,
  Accordion,
  Alert,
  Badge,
  Button,
  Group,
  NumberInput,
  Stack,
  Text,
  TextInput,
  Textarea,
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useApi } from '../../api/apiContext';
import type { Snapshot, TestCase } from '../../snapshot/model';
import { runTests } from '../../snapshot/testRunner';
import type { TestResult } from '../../snapshot/testRunner';

interface Props {
  slug: string;
  integrationKey: string;
  /** Versão salva em que os testes rodam; nula quando há alterações não salvas. */
  versionNo: number | null;
  snapshot: Snapshot;
  readOnly: boolean;
  onChange: (next: Snapshot) => void;
}

function pretty(value: unknown): string {
  return value === undefined ? '' : JSON.stringify(value, null, 2);
}

function parse(text: string): { ok: true; value: unknown } | { ok: false } {
  if (text.trim() === '') {
    return { ok: true, value: undefined };
  }
  try {
    return { ok: true, value: JSON.parse(text) as unknown };
  } catch {
    return { ok: false };
  }
}

function JsonField({
  label,
  value,
  readOnly,
  onValue,
}: {
  label: string;
  value: unknown;
  readOnly: boolean;
  onValue: (value: unknown) => void;
}) {
  const [text, setText] = useState(pretty(value));
  const parsed = parse(text);
  return (
    <Textarea
      label={label}
      autosize
      minRows={2}
      value={text}
      readOnly={readOnly}
      error={parsed.ok ? undefined : 'JSON inválido'}
      onChange={(e) => {
        const next = e.currentTarget.value;
        setText(next);
        const result = parse(next);
        if (result.ok) {
          onValue(result.value);
        }
      }}
    />
  );
}

/** Aba de casos de teste da versão: entrada, status esperado e trecho esperado do corpo. */
export function TestsTab({ slug, integrationKey, versionNo, snapshot, readOnly, onChange }: Props) {
  const api = useApi();
  const tests = snapshot.tests ?? [];
  const [results, setResults] = useState<TestResult[] | null>(null);
  const [running, setRunning] = useState(false);
  const setTests = (next: TestCase[]) => {
    onChange({ ...snapshot, tests: next });
    setResults(null);
  };
  const update = (index: number, partial: Partial<TestCase>) => {
    setTests(tests.map((t, i) => (i === index ? { ...t, ...partial } : t)));
  };

  return (
    <Stack>
      <Group justify="space-between">
        <Text c="dimmed" size="sm">
          Os testes rodam no sandbox (HML, com a transação desfeita) e todos precisam passar para a
          revisão.
        </Text>
        <Button
          disabled={versionNo === null || tests.length === 0}
          loading={running}
          onClick={() => {
            if (versionNo === null) {
              return;
            }
            setRunning(true);
            void runTests(api, slug, integrationKey, versionNo, tests).then((r) => {
              setResults(r);
              setRunning(false);
            });
          }}
        >
          Executar os testes
        </Button>
      </Group>
      {versionNo === null && (
        <Alert color="yellow">Salve o rascunho para executar os testes.</Alert>
      )}
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
      <Accordion multiple variant="separated">
        {tests.map((test, index) => (
          <Accordion.Item key={index} value={String(index)}>
            <Accordion.Control>
              <Group>
                <Text>{test.name || `Teste ${String(index + 1)}`}</Text>
                {results?.[index] && (
                  <Badge color={results[index].passed ? 'green' : 'red'}>
                    {results[index].passed ? 'passou' : 'falhou'}
                  </Badge>
                )}
              </Group>
            </Accordion.Control>
            <Accordion.Panel>
              <Stack>
                <TextInput
                  label="Nome"
                  value={test.name}
                  readOnly={readOnly}
                  onChange={(e) => {
                    update(index, { name: e.currentTarget.value });
                  }}
                />
                <JsonField
                  label="Parâmetros (JSON)"
                  value={test.request?.params}
                  readOnly={readOnly}
                  onValue={(value) => {
                    update(index, {
                      request: { ...test.request, params: value as Record<string, unknown> },
                    });
                  }}
                />
                <JsonField
                  label="Corpo (JSON)"
                  value={test.request?.body}
                  readOnly={readOnly}
                  onValue={(value) => {
                    update(index, { request: { ...test.request, body: value } });
                  }}
                />
                <Group grow align="flex-start">
                  <NumberInput
                    label="Status esperado"
                    min={100}
                    max={599}
                    value={test.expect.status}
                    readOnly={readOnly}
                    onChange={(value) => {
                      update(index, { expect: { ...test.expect, status: Number(value) } });
                    }}
                  />
                  <JsonField
                    label="O corpo deve conter (JSON)"
                    value={test.expect.bodyContains}
                    readOnly={readOnly}
                    onValue={(value) => {
                      update(index, { expect: { ...test.expect, bodyContains: value } });
                    }}
                  />
                </Group>
                {!readOnly && (
                  <ActionIcon
                    variant="subtle"
                    color="red"
                    aria-label={`Remover o teste ${String(index + 1)}`}
                    onClick={() => {
                      setTests(tests.filter((_, i) => i !== index));
                    }}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                )}
              </Stack>
            </Accordion.Panel>
          </Accordion.Item>
        ))}
      </Accordion>
      {!readOnly && (
        <Button
          variant="default"
          leftSection={<IconPlus size={16} />}
          w="fit-content"
          onClick={() => {
            setTests([
              ...tests,
              { name: `Teste ${String(tests.length + 1)}`, expect: { status: 200 } },
            ]);
          }}
        >
          Adicionar teste
        </Button>
      )}
    </Stack>
  );
}
