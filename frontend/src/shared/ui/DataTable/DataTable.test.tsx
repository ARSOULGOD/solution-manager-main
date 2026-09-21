import * as dataGrid from '@mui/x-data-grid';
import { describe, expect, it, vi } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';

import { DataTable } from './DataTable';

interface Row {
  id: string;
  name: string;
}

const rows: Row[] = [
  { id: 'a', name: 'alpha' },
  { id: 'b', name: 'beta' },
];

const columns = [{ field: 'name', headerName: 'Name', info: 'Row identity' }];

describe('DataTable', () => {
  it('keeps selection controlled and announces the expander', () => {
    const onSelectionChange = vi.fn();
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        selection={['a']}
        onSelectionChange={onSelectionChange}
        detailPanel={(row) => <div>Detail {row.name}</div>}
      />,
    );

    const expander = screen.getAllByRole('button', { name: 'Expand row details' })[0];
    expect(expander).toBeDefined();
    if (expander === undefined) {
      return;
    }
    expect(expander.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(expander);
    expect(screen.getByText('Detail alpha')).not.toBeNull();
    expect(
      screen.getByRole('button', { name: 'Collapse row details' }).getAttribute('aria-expanded'),
    ).toBe('true');
  });

  it('does not silently drop a selected id when the caller narrows `rows` (e.g. a filter) out from under it', () => {
    const onSelectionChange = vi.fn();
    const { rerender } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        selection={['a', 'b']}
        onSelectionChange={onSelectionChange}
      />,
    );

    // Simulate a caller-side filter (e.g. InstancesPage's env filter)
    // narrowing `rows` while 'b' stays selected.
    rerender(
      <DataTable
        rows={[{ id: 'a', name: 'alpha' }]}
        columns={columns}
        getRowId={(row) => row.id}
        selection={['a', 'b']}
        onSelectionChange={onSelectionChange}
      />,
    );

    expect(onSelectionChange).not.toHaveBeenCalledWith(expect.not.arrayContaining(['b']));
  });

  /**
   * Select-all is a DIFFERENT selection model from a row click, not just a
   * louder one: MUI emits `{ type: 'exclude', ids }` — "every row except
   * these" — where a row click emits `{ type: 'include', ids }`. A handler
   * that reads only `ids` therefore reads select-all as "select nothing"
   * and silently clears the selection. Verified against `@mui/x-data-grid`
   * 9.11.0's `GridRowSelectionModel` and observed in the browser.
   */
  describe('select all', () => {
    function renderTable(selection: string[], onSelectionChange: (ids: string[]) => void) {
      return renderWithProviders(
        <DataTable
          rows={rows}
          columns={columns}
          getRowId={(row) => row.id}
          selection={selection}
          onSelectionChange={onSelectionChange}
        />,
      );
    }

    it('selects every visible row', () => {
      const onSelectionChange = vi.fn();
      renderTable([], onSelectionChange);

      fireEvent.click(screen.getByRole('checkbox', { name: 'Select all rows' }));

      expect(onSelectionChange).toHaveBeenCalledWith(['a', 'b']);
    });

    it('deselects every visible row when everything is already selected', () => {
      const onSelectionChange = vi.fn();
      renderTable(['a', 'b'], onSelectionChange);

      // The header checkbox renames itself once everything is selected —
      // asserting the name here also pins that the controlled model was
      // actually applied, not merely accepted.
      fireEvent.click(screen.getByRole('checkbox', { name: 'Unselect all rows' }));

      expect(onSelectionChange).toHaveBeenCalledWith([]);
    });

    it('selects only the VISIBLE rows, preserving a selection the caller filtered away', () => {
      const onSelectionChange = vi.fn();
      renderWithProviders(
        <DataTable
          rows={[{ id: 'a', name: 'alpha' }]}
          columns={columns}
          getRowId={(row) => row.id}
          // 'b' is selected but filtered out of `rows` by the caller.
          selection={['b']}
          onSelectionChange={onSelectionChange}
        />,
      );

      fireEvent.click(screen.getByRole('checkbox', { name: 'Select all rows' }));

      const lastCall = onSelectionChange.mock.calls.at(-1);
      const ids: string[] = lastCall === undefined ? [] : (lastCall[0] as string[]);
      expect(new Set(ids)).toEqual(new Set(['a', 'b']));
    });
  });

  it('a focusRowId row expands its detail panel and receives DOM focus on arrival', () => {
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        detailPanel={(row) => <div>Detail {row.name}</div>}
        focusRowId="b"
      />,
    );

    expect(screen.getByText('Detail beta')).not.toBeNull();
    expect(document.activeElement?.closest('[role="row"]')?.getAttribute('data-id')).toBe('b');
  });

  it('does not import a Pro/Premium API', () => {
    expect('DataGridPro' in dataGrid).toBe(false);
    expect('DataGridPremium' in dataGrid).toBe(false);
  });

  it('the Columns trigger is labelled "Columns", with "Show columns" as the menu caption (matches the reference)', () => {
    renderWithProviders(<DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />);

    fireEvent.click(screen.getByRole('button', { name: 'Columns' }));
    expect(screen.getByText('Show columns')).not.toBeNull();
  });

  it('hides a hideable column via the Columns menu and keeps a non-hideable column always shown', () => {
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={[
          { field: 'name', headerName: 'Name', hideable: false },
          { field: 'id', headerName: 'Id' },
        ]}
        getRowId={(row) => row.id}
      />,
    );

    expect(screen.getAllByRole('columnheader', { name: 'Id' }).length).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Columns' }));
    expect(screen.queryByRole('menuitemcheckbox', { name: 'Name' })).toBeNull();
    const idToggle = screen.getByRole('menuitemcheckbox', { name: 'Id' });
    expect(idToggle.getAttribute('aria-checked')).toBe('true');

    fireEvent.click(idToggle);
    fireEvent.keyDown(idToggle, { key: 'Escape' });

    expect(screen.queryAllByRole('columnheader', { name: 'Id' }).length).toBe(0);
  });

  it('renders toolbarExtras next to the built-in toolbar controls', () => {
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        toolbarExtras={<button type="button">Env filter</button>}
      />,
    );

    expect(screen.getByRole('button', { name: 'Env filter' })).not.toBeNull();
  });

  it('renderCell renders a custom visual per row, independent of valueGetter (sort/CSV value)', () => {
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={[
          {
            field: 'name',
            headerName: 'Name',
            valueGetter: (row) => row.name,
            renderCell: (row) => <strong data-custom-cell>{row.name.toUpperCase()}</strong>,
          },
        ]}
        getRowId={(row) => row.id}
      />,
    );

    expect(screen.getByText('ALPHA')).not.toBeNull();
    expect(screen.getByText('BETA')).not.toBeNull();
    expect(document.querySelectorAll('[data-custom-cell]').length).toBe(2);
  });
});

describe('DataTable responsive (task 107, FE-D29)', () => {
  it('renders cards, not the grid, below md', () => {
    setViewportWidth(320);
    renderWithProviders(<DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />);

    expect(document.querySelector('[data-card-view]')).not.toBeNull();
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('renders the grid, not cards, at md and up', () => {
    setViewportWidth(1280);
    renderWithProviders(<DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />);

    expect(document.querySelector('[data-card-view]')).toBeNull();
    expect(screen.getByRole('grid')).not.toBeNull();
  });

  it('a card checkbox reports the row id via onSelectionChange, same contract as the grid', () => {
    setViewportWidth(320);
    const onSelectionChange = vi.fn();
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        selection={['a']}
        onSelectionChange={onSelectionChange}
      />,
    );

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select beta' }));
    expect(onSelectionChange).toHaveBeenCalledWith(['a', 'b']);

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select alpha' }));
    expect(onSelectionChange).toHaveBeenCalledWith([]);
  });

  it('a card expands its detail panel inline on the expander click, same content as the grid', () => {
    setViewportWidth(320);
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        detailPanel={(row) => <div>Detail {row.name}</div>}
      />,
    );

    const expander = screen.getAllByRole('button', { name: 'Expand row details' })[0];
    expect(expander).toBeDefined();
    if (expander === undefined) {
      return;
    }
    fireEvent.click(expander);

    expect(screen.getByText('Detail alpha')).not.toBeNull();
    expect(
      screen.getByRole('button', { name: 'Collapse row details' }).getAttribute('aria-expanded'),
    ).toBe('true');
  });

  it('a focusRowId card receives DOM focus on arrival, same contract as the grid', () => {
    setViewportWidth(320);
    renderWithProviders(
      <DataTable rows={rows} columns={columns} getRowId={(row) => row.id} focusRowId="b" />,
    );

    expect(document.activeElement?.closest('[data-card-row]')?.getAttribute('data-card-row')).toBe(
      'b',
    );
  });

  it('sort chosen in the grid still orders the cards after a resize below md', () => {
    setViewportWidth(1280);
    const { rerender } = renderWithProviders(
      <DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />,
    );

    // Descending by name: beta, then alpha.
    fireEvent.click(screen.getByRole('columnheader', { name: /Name/ }));
    fireEvent.click(screen.getByRole('columnheader', { name: /Name/ }));

    setViewportWidth(320);
    rerender(<DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />);

    const cardNames = [...document.querySelectorAll('[data-card-row]')].map((el) =>
      el.getAttribute('data-card-row'),
    );
    expect(cardNames).toEqual(['b', 'a']);
  });

  it('bulk actions render inline in the toolbar at md and up, not as a bottom bar', () => {
    setViewportWidth(1280);
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        bulkActions={<button type="button">Do the thing</button>}
      />,
    );

    expect(screen.getByRole('button', { name: 'Do the thing' })).not.toBeNull();
    expect(document.querySelector('[data-bulk-actions-bar]')).toBeNull();
  });

  it('bulk actions move to a sticky bottom bar below md', () => {
    setViewportWidth(320);
    renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        bulkActions={<button type="button">Do the thing</button>}
      />,
    );

    const bar = document.querySelector('[data-bulk-actions-bar]');
    expect(bar).not.toBeNull();
    expect(bar?.querySelector('button')?.textContent).toBe('Do the thing');
  });

  it('no bulk-actions bar renders below md when there is nothing to act on', () => {
    setViewportWidth(320);
    renderWithProviders(
      <DataTable rows={rows} columns={columns} getRowId={(row) => row.id} bulkActions={false} />,
    );

    expect(document.querySelector('[data-bulk-actions-bar]')).toBeNull();
  });

  it('does not force page-body horizontal scroll at the 320px reflow floor', () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        detailPanel={(row) => <div>Detail {row.name}</div>}
        bulkActions={<button type="button">Do the thing</button>}
      />,
    );

    expect(() => {
      assertReflow(container.firstElementChild as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });
});
