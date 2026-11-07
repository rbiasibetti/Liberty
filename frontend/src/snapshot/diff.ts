export interface Change {
  path: string;
  kind: 'added' | 'removed' | 'changed';
  before?: unknown;
  after?: unknown;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function walk(before: unknown, after: unknown, path: string, out: Change[]) {
  if (isObject(before) && isObject(after)) {
    const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort();
    for (const key of keys) {
      const here = `${path}/${key.replace(/~/g, '~0').replace(/\//g, '~1')}`;
      if (!(key in before)) {
        out.push({ path: here, kind: 'added', after: after[key] });
      } else if (!(key in after)) {
        out.push({ path: here, kind: 'removed', before: before[key] });
      } else {
        walk(before[key], after[key], here, out);
      }
    }
    return;
  }
  if (Array.isArray(before) && Array.isArray(after)) {
    const length = Math.max(before.length, after.length);
    for (let i = 0; i < length; i++) {
      const here = `${path}/${String(i)}`;
      if (i >= before.length) {
        out.push({ path: here, kind: 'added', after: after[i] });
      } else if (i >= after.length) {
        out.push({ path: here, kind: 'removed', before: before[i] });
      } else {
        walk(before[i], after[i], here, out);
      }
    }
    return;
  }
  if (JSON.stringify(before) !== JSON.stringify(after)) {
    out.push({ path: path || '/', kind: 'changed', before, after });
  }
}

/** Mudanças entre dois documentos, uma por valor, com o ponteiro JSON de cada uma. */
export function diffDocuments(before: unknown, after: unknown): Change[] {
  const out: Change[] = [];
  walk(before, after, '', out);
  return out;
}

export interface SnapshotDiff {
  /** Tudo, exceto os scripts (que vão para o editor de diff). */
  changes: Change[];
  scripts: { beforeExecute: [string, string]; afterExecute: [string, string] };
}

function scriptOf(doc: unknown, name: 'beforeExecute' | 'afterExecute'): string {
  const scripts = isObject(doc) && isObject(doc.scripts) ? doc.scripts : {};
  const value = scripts[name];
  return typeof value === 'string' ? value : '';
}

/** Diferença de dois snapshots: mudanças estruturais e os pares de scripts para o diff de texto. */
export function diffSnapshots(before: unknown, after: unknown): SnapshotDiff {
  const strip = (doc: unknown) => {
    if (!isObject(doc)) {
      return doc;
    }
    const rest = { ...doc };
    delete rest.scripts;
    return rest;
  };
  return {
    changes: diffDocuments(strip(before), strip(after)),
    scripts: {
      beforeExecute: [scriptOf(before, 'beforeExecute'), scriptOf(after, 'beforeExecute')],
      afterExecute: [scriptOf(before, 'afterExecute'), scriptOf(after, 'afterExecute')],
    },
  };
}
