import type { ReactNode } from 'react';
import { Group, Panel, Separator } from 'react-resizable-panels';

/** Área principal e painel lateral redimensionável (o sandbox fica ao lado do editor). */
export function Split({ main, side }: { main: ReactNode; side: ReactNode }) {
  return (
    <Group orientation="horizontal" style={{ minHeight: 'calc(100vh - 160px)' }}>
      <Panel defaultSize={65} minSize={35}>
        <div style={{ height: '100%', overflow: 'auto', paddingRight: 8 }}>{main}</div>
      </Panel>
      <Separator
        style={{
          width: 6,
          background: 'var(--mantine-color-default-border)',
          cursor: 'col-resize',
        }}
      />
      <Panel defaultSize={35} minSize={20}>
        <div style={{ height: '100%', overflow: 'auto', paddingLeft: 8 }}>{side}</div>
      </Panel>
    </Group>
  );
}
