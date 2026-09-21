import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import type { FieldDeclaration } from '@contracts/view-model/types';

import { renderWithProviders } from '../../../../test/renderWithProviders';

import { TableControl } from './TableControl';
import type { TableRow } from './TableControl.types';

const columns: readonly FieldDeclaration[] = [
  { key: 'path', type: 'string' },
  { key: 'priority', type: 'int' },
  { key: 'enabled', type: 'bool' },
  { key: 'mode', type: 'enum', options: ['push', 'pull'] },
];

const rows: readonly TableRow[] = [{ path: '/data', priority: 1, enabled: true, mode: 'push' }];

const modes = ['light', 'dark'] as const;

describe('TableControl a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <TableControl label="Sources" columns={columns} rows={rows} onChange={() => {}} />
        <TableControl label="No columns declared" columns={undefined} rows={[]} onChange={() => {}} />
        <TableControl label="Disabled" columns={columns} rows={rows} onChange={() => {}} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
