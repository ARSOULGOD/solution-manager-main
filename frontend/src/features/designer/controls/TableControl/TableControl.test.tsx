import { fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { FieldDeclaration } from '@contracts/view-model/types';

import { renderWithProviders, screen } from '../../../../test/renderWithProviders';

import { TableControl } from './TableControl';
import type { TableRow } from './TableControl.types';

const columns: readonly FieldDeclaration[] = [
  { key: 'path', type: 'string' },
  { key: 'priority', type: 'int' },
  { key: 'enabled', type: 'bool' },
  { key: 'mode', type: 'enum', options: ['push', 'pull'] },
];

const rows: readonly TableRow[] = [{ path: '/data', priority: 1, enabled: true, mode: 'push' }];

describe('TableControl', () => {
  it('renders the "declared by the package" placeholder when there are no columns', () => {
    renderWithProviders(<TableControl label="Sources" columns={undefined} rows={[]} onChange={() => {}} />);

    expect(
      screen.getByText('This table’s columns are declared by the Solution Package.'),
    ).toBeInTheDocument();
  });

  it('renders the same placeholder for an empty columns array', () => {
    renderWithProviders(<TableControl label="Sources" columns={[]} rows={[]} onChange={() => {}} />);

    expect(
      screen.getByText('This table’s columns are declared by the Solution Package.'),
    ).toBeInTheDocument();
  });

  it('renders one labelled cell per declared column, per row', () => {
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={rows} onChange={() => {}} />);

    expect(screen.getByLabelText<HTMLInputElement>('path · Row 1').value).toBe('/data');
    expect(screen.getByLabelText<HTMLInputElement>('priority · Row 1').value).toBe('1');
    expect(screen.getByLabelText<HTMLInputElement>('enabled · Row 1').checked).toBe(true);
    expect(screen.getByLabelText('mode · Row 1')).toHaveTextContent('push');
  });

  it('edits a text cell without disturbing other columns in the row', () => {
    const onChange = vi.fn();
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={rows} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('path · Row 1'), { target: { value: '/other' } });

    expect(onChange).toHaveBeenCalledWith([{ ...rows[0], path: '/other' }]);
  });

  it('edits a boolean cell via the toggle', () => {
    const onChange = vi.fn();
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={rows} onChange={onChange} />);

    fireEvent.click(screen.getByLabelText('enabled · Row 1'));

    expect(onChange).toHaveBeenCalledWith([{ ...rows[0], enabled: false }]);
  });

  it('edits an enum cell via the select', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={rows} onChange={onChange} />);

    await user.click(screen.getByLabelText('mode · Row 1'));
    await user.click(await screen.findByRole('option', { name: 'pull' }));

    expect(onChange).toHaveBeenCalledWith([{ ...rows[0], mode: 'pull' }]);
  });

  it('appends a blank row seeded from the declared columns', () => {
    const onChange = vi.fn();
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={rows} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Add row' }));

    expect(onChange).toHaveBeenCalledWith([
      ...rows,
      { path: null, priority: null, enabled: false, mode: null },
    ]);
  });

  it('removes only the targeted row', () => {
    const onChange = vi.fn();
    const twoRows: readonly TableRow[] = [
      { path: '/data', priority: 1, enabled: true, mode: 'push' },
      { path: '/other', priority: 2, enabled: false, mode: 'pull' },
    ];
    renderWithProviders(<TableControl label="Sources" columns={columns} rows={twoRows} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Remove row 1' }));

    expect(onChange).toHaveBeenCalledWith([twoRows[1]]);
  });
});
