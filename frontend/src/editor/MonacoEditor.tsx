import { DiffEditor, Editor } from '@monaco-editor/react';
import { monaco } from './monacoSetup';

export interface CodeEditorProps {
  value: string;
  onChange?: (value: string) => void;
  language: 'json' | 'javascript';
  height?: number | string;
  /** Declarações (.d.ts) para autocompletar e checar scripts. */
  typings?: string;
  readOnly?: boolean;
  label: string;
}

const OPTIONS = {
  minimap: { enabled: false },
  scrollBeyondLastLine: false,
  automaticLayout: true,
  fontSize: 13,
  tabSize: 2,
} as const;

export default function MonacoEditor({
  value,
  onChange,
  language,
  height = 320,
  typings,
  readOnly,
  label,
}: CodeEditorProps) {
  return (
    <div aria-label={label} style={{ border: '1px solid var(--mantine-color-default-border)' }}>
      <Editor
        height={height}
        language={language}
        value={value}
        path={`file:///${label.replace(/\W+/g, '-')}.${language === 'json' ? 'json' : 'js'}`}
        options={{ ...OPTIONS, readOnly: readOnly ?? false }}
        beforeMount={() => {
          if (typings) {
            const js = monaco.typescript.javascriptDefaults;
            js.setCompilerOptions({
              allowNonTsExtensions: true,
              checkJs: true,
              target: monaco.typescript.ScriptTarget.ESNext,
            });
            // por padrão o Monaco não checa tipos em JavaScript
            js.setDiagnosticsOptions({ noSemanticValidation: false, noSyntaxValidation: false });
            js.setEagerModelSync(true);
            js.setExtraLibs([{ content: typings, filePath: 'file:///liberty-api.d.ts' }]);
          }
        }}
        onChange={(next) => {
          onChange?.(next ?? '');
        }}
      />
    </div>
  );
}

export function MonacoDiff({
  original,
  modified,
  language,
  height = 280,
}: {
  original: string;
  modified: string;
  language: 'json' | 'javascript';
  height?: number | string;
}) {
  return (
    <DiffEditor
      height={height}
      language={language}
      original={original}
      modified={modified}
      options={{ ...OPTIONS, readOnly: true, renderSideBySide: true }}
    />
  );
}
