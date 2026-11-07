import { Loader } from '@mantine/core';
import { lazy, Suspense } from 'react';
import type { CodeEditorProps } from './MonacoEditor';

// o Monaco é grande: só é baixado quando uma tela com editor abre
const Monaco = lazy(() => import('./MonacoEditor'));
const Diff = lazy(() => import('./MonacoEditor').then((m) => ({ default: m.MonacoDiff })));

export function CodeEditor(props: CodeEditorProps) {
  return (
    <Suspense fallback={<Loader aria-label="Carregando o editor" />}>
      <Monaco {...props} />
    </Suspense>
  );
}

export function DiffViewer(props: {
  original: string;
  modified: string;
  language: 'json' | 'javascript';
  height?: number | string;
}) {
  return (
    <Suspense fallback={<Loader aria-label="Carregando o editor" />}>
      <Diff {...props} />
    </Suspense>
  );
}
