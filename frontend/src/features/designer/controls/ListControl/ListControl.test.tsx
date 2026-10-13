import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { setViewportWidth } from '../../../../test/matchMedia';
import { renderWithProviders, screen } from '../../../../test/renderWithProviders';

import { ListControl } from './ListControl';

describe('ListControl', () => {
  it('labels each row distinctly for assistive technology', () => {
    renderWithProviders(
      <ListControl label="Allowed hosts" values={['a.example', 'b.example']} onChange={() => {}} />,
    );

    expect(screen.getByLabelText<HTMLInputElement>('Item 1').value).toBe('a.example');
    expect(screen.getByLabelText<HTMLInputElement>('Item 2').value).toBe('b.example');
  });

  it('edits a row in place without disturbing the others', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <ListControl label="Allowed hosts" values={['a.example', 'b.example']} onChange={onChange} />,
    );

    fireEvent.change(screen.getByLabelText('Item 2'), { target: { value: 'c.example' } });

    expect(onChange).toHaveBeenCalledWith(['a.example', 'c.example']);
  });

  it('appends a new empty row', () => {
    const onChange = vi.fn();
    renderWithProviders(<ListControl label="Allowed hosts" values={['a.example']} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Add item' }));

    expect(onChange).toHaveBeenCalledWith(['a.example', '']);
  });

  it('removes only the targeted row', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <ListControl
        label="Allowed hosts"
        values={['a.example', 'b.example', 'c.example']}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Remove item 2' }));

    expect(onChange).toHaveBeenCalledWith(['a.example', 'c.example']);
  });

  it('renders the group label as a fieldset legend, distinct from row labels', () => {
    renderWithProviders(<ListControl label="Allowed hosts" values={['a.example']} onChange={() => {}} />);

    expect(screen.getByText('Allowed hosts').tagName).toBe('LEGEND');
  });

  it('renders plain helper text when there is no error', () => {
    renderWithProviders(
      <ListControl
        label="Allowed hosts"
        values={['a.example']}
        onChange={() => {}}
        helperText="One host per row."
      />,
    );

    expect(screen.getByText('One host per row.')).not.toBeNull();
  });

  it('renders the error message in place of helper text, in a colour distinct from plain helper text — this is `FieldRow`\'s only error display for `list` fields once the native-error kinds\' duplicate is filtered', () => {
    const helperOnly = renderWithProviders(
      <ListControl label="Allowed hosts" values={[]} onChange={() => {}} helperText="One host per row." />,
    );
    const helperColor = getComputedStyle(helperOnly.getByText('One host per row.')).color;
    helperOnly.unmount();

    renderWithProviders(
      <ListControl
        label="Allowed hosts"
        values={[]}
        onChange={() => {}}
        helperText="One host per row."
        error="This field is required."
      />,
    );

    expect(screen.queryByText('One host per row.')).toBeNull();
    const message = screen.getByText('This field is required.');
    expect(getComputedStyle(message).color).not.toBe(helperColor);
  });
});

describe('ListControl below sm (task 108, FE-D29)', () => {
  it('stacks each row instead of sitting the input and remove button side by side', () => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <ListControl label="Allowed hosts" values={['a.example']} onChange={() => {}} />,
    );

    const row = container.querySelector('[data-list-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    expect(getComputedStyle(row as HTMLElement).flexDirection).toBe('column');
  });

  it('stays a row at sm and up', () => {
    setViewportWidth(600);
    const { container } = renderWithProviders(
      <ListControl label="Allowed hosts" values={['a.example']} onChange={() => {}} />,
    );

    const row = container.querySelector('[data-list-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    expect(getComputedStyle(row as HTMLElement).flexDirection).toBe('row');
  });
});
