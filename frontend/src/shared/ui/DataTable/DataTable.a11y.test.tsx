import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { setViewportWidth } from '../../../test/matchMedia';
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

const modes = ['light', 'dark'] as const;

describe('DataTable a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DataTable rows={rows} columns={columns} getRowId={(row) => row.id} />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the expander column header has an accessible name in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        detailPanel={(row) => <div>Detail {row.name}</div>}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the open Columns menu has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={[
          { field: 'name', headerName: 'Name' },
          { field: 'id', headerName: 'Id' },
        ]}
        getRowId={(row) => row.id}
      />,
      { mode },
    );

    fireEvent.click(screen.getByRole('button', { name: 'Columns' }));

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('the card view below md has no violations in %s mode', async (mode) => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        selection={['a']}
        onSelectionChange={() => undefined}
        detailPanel={(row) => <div>Detail {row.name}</div>}
        bulkActions={<button type="button">Do the thing</button>}
      />,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('an expanded card in the card view has no violations in %s mode', async (mode) => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <DataTable
        rows={rows}
        columns={columns}
        getRowId={(row) => row.id}
        detailPanel={(row) => <div>Detail {row.name}</div>}
      />,
      { mode },
    );

    const expander = screen.getAllByRole('button', { name: 'Expand row details' })[0];
    expect(expander).toBeDefined();
    if (expander === undefined) {
      return;
    }
    fireEvent.click(expander);

    expect(await axe(container)).toHaveNoViolations();
  });
});
