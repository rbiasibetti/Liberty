import { useEffect, useState } from 'react';
import { describeError } from './problem';

export interface RequestState<T> {
  status: 'loading' | 'ready' | 'error';
  data: T | undefined;
  /** Mensagem pronta para o usuário quando `status` é `error`. */
  error: string | undefined;
  reload: () => void;
}

/**
 * Executa uma chamada e acompanha o resultado. Muda de `key` (ou recarrega) volta para `loading`
 * sem gravar estado dentro do efeito; `run` deve ser estável (useCallback).
 */
export function useRequest<T>(key: string, run: () => Promise<T>): RequestState<T> {
  const [version, setVersion] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    data?: T;
    error?: string;
  } | null>(null);
  const current = `${key}#${String(version)}`;

  useEffect(() => {
    let active = true;
    run().then(
      (data) => {
        if (active) {
          setResult({ key: current, data });
        }
      },
      (reason: unknown) => {
        if (active) {
          setResult({ key: current, error: describeError(reason) });
        }
      },
    );
    return () => {
      active = false;
    };
  }, [current, run]);

  const done = result?.key === current ? result : null;
  return {
    status: done === null ? 'loading' : done.error === undefined ? 'ready' : 'error',
    data: done?.data,
    error: done?.error,
    reload: () => {
      setVersion((v) => v + 1);
    },
  };
}
