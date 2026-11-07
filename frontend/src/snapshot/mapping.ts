import type { components } from '../api/schema';
import type { Field, WriteNode } from './model';
import type { PointerInfo } from './pointers';

export type CatalogColumn = components['schemas']['CatalogColumn'];

/** Caminho de um nó na árvore: índices dentro de `children`, a partir da raiz (vazio = raiz). */
export type NodePath = number[];

export const MAX_DEPTH = 5;

export function getNode(root: WriteNode, path: NodePath): WriteNode | undefined {
  let node: WriteNode | undefined = root;
  for (const index of path) {
    node = node?.children?.[index];
  }
  return node;
}

/** Devolve uma cópia da árvore com o nó em `path` substituído pelo resultado de `change`. */
export function updateNode(
  root: WriteNode,
  path: NodePath,
  change: (node: WriteNode) => WriteNode,
): WriteNode {
  const [head, ...rest] = path;
  if (head === undefined) {
    return change(root);
  }
  const children = (root.children ?? []).map((child, i) =>
    i === head ? updateNode(child, rest, change) : child,
  );
  return { ...root, children };
}

export function emptyNode(): WriteNode {
  return { table: '', fields: [] };
}

export function addChild(root: WriteNode, path: NodePath): WriteNode {
  return updateNode(root, path, (node) => ({
    ...node,
    children: [...(node.children ?? []), { ...emptyNode(), arrayPath: '', foreignKey: '' }],
  }));
}

/** Remove o nó em `path` (a raiz não se remove). */
export function removeNode(root: WriteNode, path: NodePath): WriteNode {
  const parent = path.slice(0, -1);
  const index = path[path.length - 1];
  if (index === undefined) {
    return root;
  }
  return updateNode(root, parent, (node) => ({
    ...node,
    children: (node.children ?? []).filter((_, i) => i !== index),
  }));
}

export function depthOf(root: WriteNode): number {
  return 1 + Math.max(0, ...(root.children ?? []).map(depthOf));
}

const normalize = (name: string) => name.toLowerCase().replace(/[^a-z0-9]/g, '');

/**
 * Sugere os campos que faltam: cada coluna gravável sem mapeamento cujo nome casa (sem maiúsculas
 * nem separadores) com uma propriedade do corpo vira um campo `source`.
 */
export function autoMapFields(
  columns: CatalogColumn[],
  pointers: PointerInfo[],
  existing: Field[],
  skip: string[] = [],
): Field[] {
  const used = new Set(existing.map((f) => f.column.toLowerCase()));
  skip.forEach((name) => used.add(name.toLowerCase()));
  const added: Field[] = [];
  for (const column of columns) {
    if (column.generated || used.has(column.name.toLowerCase())) {
      continue;
    }
    const match = pointers.find(
      (p) =>
        p.type !== 'object' &&
        p.type !== 'array' &&
        normalize(p.pointer.split('/').pop() ?? '') === normalize(column.name),
    );
    if (match) {
      added.push({ column: column.name, source: match.pointer });
    }
  }
  return [...existing, ...added];
}
