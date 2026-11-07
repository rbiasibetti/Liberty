import { Stack, Text } from '@mantine/core';
import { useMemo } from 'react';
import { CodeEditor } from '../../editor/CodeEditor';
import type { Snapshot } from '../../snapshot/model';
import { scriptTypings } from '../../snapshot/scriptTypes';

interface Props {
  snapshot: Snapshot;
  readOnly: boolean;
  onChange: (next: Snapshot) => void;
}

const BEFORE = `/** @param {BeforeInput} input */
function beforeExecute(input) {
  // input.params e input.body; devolva o corpo (alterado) ou nada para mantê-lo
  return input.body;
}
`;

const AFTER = `/** @param {AfterInput} input */
function afterExecute(input) {
  // input.result é a resposta; devolva a nova resposta ou nada para mantê-la
  return input.result;
}
`;

/** Aba de scripts: Monaco com os tipos da API de script gerados do JSON Schema da versão. */
export function ScriptsTab({ snapshot, readOnly, onChange }: Props) {
  const typings = useMemo(
    () => scriptTypings(snapshot.request.bodySchema),
    [snapshot.request.bodySchema],
  );
  const scripts = snapshot.scripts ?? {};
  const set = (name: 'beforeExecute' | 'afterExecute', value: string) => {
    onChange({ ...snapshot, scripts: { ...scripts, [name]: value.trim() === '' ? null : value } });
  };
  return (
    <Stack>
      <Text c="dimmed" size="sm">
        Scripts rodam em processos isolados, com prazo e memória limitados. Só leem dados
        (`db.named`, `db.lookup`) e podem recusar a operação com `fail(status, mensagem)`.
      </Text>
      {snapshot.operation.type !== 'SELECT' && (
        <>
          <Text fw={600}>beforeExecute</Text>
          <CodeEditor
            label="beforeExecute"
            language="javascript"
            typings={typings}
            value={scripts.beforeExecute ?? BEFORE}
            readOnly={readOnly}
            height={220}
            onChange={(value) => {
              set('beforeExecute', value);
            }}
          />
        </>
      )}
      <Text fw={600}>afterExecute</Text>
      <CodeEditor
        label="afterExecute"
        language="javascript"
        typings={typings}
        value={scripts.afterExecute ?? AFTER}
        readOnly={readOnly}
        height={220}
        onChange={(value) => {
          set('afterExecute', value);
        }}
      />
    </Stack>
  );
}
