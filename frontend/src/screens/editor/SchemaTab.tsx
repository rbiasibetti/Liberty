import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { CodeEditor } from '../../editor/CodeEditor';
import type { Snapshot } from '../../snapshot/model';
import { schemaFromSample } from '../../snapshot/schemaFromSample';

interface Props {
  snapshot: Snapshot;
  readOnly: boolean;
  onChange: (next: Snapshot) => void;
}

function Generator({
  onGenerate,
  onClose,
}: {
  onGenerate: (schema: Record<string, unknown>) => void;
  onClose: () => void;
}) {
  const [text, setText] = useState(
    '{\n  "numero": "P-1",\n  "itens": [{ "produto": "A", "qtd": 1 }]\n}',
  );
  const [error, setError] = useState<string | null>(null);
  return (
    <Modal opened onClose={onClose} title="Gerar o schema a partir de um JSON de exemplo" size="lg">
      <Stack>
        <Textarea
          label="JSON de exemplo"
          autosize
          minRows={8}
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
          }}
        />
        {error && <Alert color="red">{error}</Alert>}
        <Button
          onClick={() => {
            try {
              onGenerate(schemaFromSample(JSON.parse(text)));
              onClose();
            } catch {
              setError('O exemplo não é um JSON válido.');
            }
          }}
        >
          Gerar
        </Button>
      </Stack>
    </Modal>
  );
}

/** Aba do JSON Schema do corpo: editor com validação de sintaxe e geração a partir de um exemplo. */
export function SchemaTab({ snapshot, readOnly, onChange }: Props) {
  const [revision, setRevision] = useState(0);
  const [invalid, setInvalid] = useState(false);
  const [generating, setGenerating] = useState(false);
  const schema = snapshot.request.bodySchema ?? { type: 'object' };

  const apply = (next: Record<string, unknown>) => {
    onChange({ ...snapshot, request: { ...snapshot.request, bodySchema: next } });
    setRevision((n) => n + 1);
  };

  if (snapshot.operation.type === 'SELECT' || snapshot.operation.type === 'DELETE') {
    return (
      <Text c="dimmed">
        {snapshot.operation.type === 'DELETE'
          ? 'Remoções não têm corpo: a chave vem dos parâmetros da rota.'
          : 'Leituras não têm corpo: o JSON Schema vale só para escritas.'}
      </Text>
    );
  }
  return (
    <Stack>
      <Group justify="space-between">
        <Text fw={600}>JSON Schema do corpo (2020-12)</Text>
        {!readOnly && (
          <Button
            variant="default"
            onClick={() => {
              setGenerating(true);
            }}
          >
            Gerar a partir de um exemplo
          </Button>
        )}
      </Group>
      <CodeEditor
        key={revision}
        label="JSON Schema do corpo"
        language="json"
        value={JSON.stringify(schema, null, 2)}
        readOnly={readOnly}
        onChange={(text) => {
          try {
            const parsed: unknown = JSON.parse(text);
            if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
              setInvalid(false);
              onChange({
                ...snapshot,
                request: { ...snapshot.request, bodySchema: parsed as Record<string, unknown> },
              });
              return;
            }
          } catch {
            // continua digitando: o aviso abaixo diz que ainda não é um JSON válido
          }
          setInvalid(true);
        }}
      />
      {invalid && (
        <Alert color="yellow">
          O texto ainda não é um JSON de objeto válido; a última versão válida foi mantida.
        </Alert>
      )}
      {generating && (
        <Generator
          onGenerate={apply}
          onClose={() => {
            setGenerating(false);
          }}
        />
      )}
    </Stack>
  );
}
