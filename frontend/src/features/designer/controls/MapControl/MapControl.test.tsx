import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { setViewportWidth } from '../../../../test/matchMedia';
import { renderWithProviders, screen } from '../../../../test/renderWithProviders';

import { MapControl } from './MapControl';

const entries = [
  { key: 'region', value: 'us-east-1' },
  { key: 'tier', value: 'gold' },
];

describe('MapControl', () => {
  it('labels each row’s key and value distinctly for assistive technology', () => {
    renderWithProviders(<MapControl label="Tags" entries={entries} onChange={() => {}} />);

    expect(screen.getByLabelText<HTMLInputElement>('Key 1').value).toBe('region');
    expect(screen.getByLabelText<HTMLInputElement>('Value 1').value).toBe('us-east-1');
    expect(screen.getByLabelText<HTMLInputElement>('Key 2').value).toBe('tier');
    expect(screen.getByLabelText<HTMLInputElement>('Value 2').value).toBe('gold');
  });

  it('edits only the targeted half of a row', () => {
    const onChange = vi.fn();
    renderWithProviders(<MapControl label="Tags" entries={entries} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText('Value 2'), { target: { value: 'platinum' } });

    expect(onChange).toHaveBeenCalledWith([
      { key: 'region', value: 'us-east-1' },
      { key: 'tier', value: 'platinum' },
    ]);
  });

  it('appends a new empty key/value row', () => {
    const onChange = vi.fn();
    renderWithProviders(<MapControl label="Tags" entries={entries} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Add entry' }));

    expect(onChange).toHaveBeenCalledWith([...entries, { key: '', value: '' }]);
  });

  it('removes only the targeted row', () => {
    const onChange = vi.fn();
    renderWithProviders(<MapControl label="Tags" entries={entries} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'Remove entry 1' }));

    expect(onChange).toHaveBeenCalledWith([{ key: 'tier', value: 'gold' }]);
  });
});

describe('MapControl below sm (task 108, FE-D29)', () => {
  it('stacks the key/value pair and remove button instead of an unwrapped row', () => {
    setViewportWidth(320);
    const { container } = renderWithProviders(
      <MapControl label="Tags" entries={entries} onChange={() => {}} />,
    );

    const row = container.querySelector('[data-map-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    expect(getComputedStyle(row as HTMLElement).flexDirection).toBe('column');
  });

  it('stays a row at sm and up', () => {
    setViewportWidth(600);
    const { container } = renderWithProviders(
      <MapControl label="Tags" entries={entries} onChange={() => {}} />,
    );

    const row = container.querySelector('[data-map-row]');
    expect(row).toBeInstanceOf(HTMLElement);
    expect(getComputedStyle(row as HTMLElement).flexDirection).toBe('row');
  });
});
