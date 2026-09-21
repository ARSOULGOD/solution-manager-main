/**
 * The FE-119 regression, asserted at the seam that actually broke.
 *
 * `DataTable` used to build every `GridColDef` with **both** `width` and
 * `flex: 1`. MUI X states the interaction outright — "flex doesn't work
 * together with width. If you set both flex and width in GridColDef, flex
 * will override width." (verified against
 * https://mui.com/x/react-data-grid/column-dimensions/ 2026-08-26) — so
 * every declared width in `columns.tsx` was discarded and nine columns
 * rendered at identical width.
 *
 * Asserted against the COLUMN DEFINITIONS rather than the rendered header
 * cells: jsdom gives the grid no measurable container, so MUI's own
 * layout pass resolves every header to `width: 0px` regardless of what it
 * was told. The definitions are what this component owns; the layout is
 * MUI's, and the docs above pin its behaviour.
 */
import { createElement, type ComponentType } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { DataTable } from './DataTable';

const captured: Record<string, unknown>[][] = [];

vi.mock('@mui/x-data-grid', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@mui/x-data-grid')>();
  return {
    ...actual,
    DataGrid: (props: Record<string, unknown> & { columns: Record<string, unknown>[] }) => {
      captured.push(props.columns);
      // `createElement`, not a direct call: the real grid is a component
      // with hooks, and calling it as a function would break them.
      return createElement(
        actual.DataGrid as unknown as ComponentType<Record<string, unknown>>,
        props,
      );
    },
  };
});

const rows = [{ id: 'a', name: 'alpha', tag: 'x' }];

function columnDefs(columns: { field: string; headerName: string; width?: number }[]) {
  captured.length = 0;
  renderWithProviders(<DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />);
  const last = captured.at(-1);
  if (last === undefined) {
    throw new Error('DataGrid was never rendered');
  }
  return new Map(last.map((def) => [def['field'] as string, def]));
}

describe('DataTable column dimensions', () => {
  it('maps a declared width to minWidth — the floor — and never to width', () => {
    const defs = columnDefs([
      { field: 'name', headerName: 'Name', width: 240 },
      { field: 'tag', headerName: 'Tag', width: 88 },
    ]);

    expect(defs.get('name')?.['minWidth']).toBe(240);
    expect(defs.get('tag')?.['minWidth']).toBe(88);
    // `width` alongside `flex` is the defect: MUI discards it.
    expect(defs.get('name')?.['width']).toBeUndefined();
    expect(defs.get('tag')?.['width']).toBeUndefined();
  });

  it('gives each column a flex share PROPORTIONAL to its declared width', () => {
    const defs = columnDefs([
      { field: 'name', headerName: 'Name', width: 240 },
      { field: 'tag', headerName: 'Tag', width: 88 },
    ]);

    // Not `flex: 1` for both — that identical share is what made nine
    // columns render identically wide.
    expect(defs.get('name')?.['flex']).toBeCloseTo(2.4);
    expect(defs.get('tag')?.['flex']).toBeCloseTo(0.88);
  });

  it('falls back to 160 for a column that declares no width', () => {
    const defs = columnDefs([{ field: 'name', headerName: 'Name' }]);

    expect(defs.get('name')?.['minWidth']).toBe(160);
    expect(defs.get('name')?.['flex']).toBeCloseTo(1.6);
  });
});
