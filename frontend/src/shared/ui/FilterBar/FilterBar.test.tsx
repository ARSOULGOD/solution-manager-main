import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';
import { ToggleGroup } from '../ToggleGroup';

import { FilterBar } from './FilterBar';

describe('FilterBar', () => {
  it('announces result count politely and does not debounce enum filters', () => {
    const onEnv = vi.fn();
    renderWithProviders(
      <FilterBar resultCount={3}>
        <ToggleGroup
          aria-label="Environment"
          value="all"
          onChange={onEnv}
          options={[
            { value: 'all', label: 'All' },
            { value: 'prod', label: 'PROD' },
          ]}
        />
      </FilterBar>,
    );

    const live = document.querySelector('[data-filter-result-count]');
    expect(live?.getAttribute('aria-live')).toBe('polite');
    expect(live?.textContent).toBe('3 results');
    screen.getByRole('button', { name: 'PROD' }).click();
    expect(onEnv).toHaveBeenCalledWith('prod');
  });

  it('debounces free text and applies enum immediately', () => {
    vi.useFakeTimers();
    const onEnum = vi.fn();
    function Probe() {
      const [q, setQ] = useState('');
      return (
        <FilterBar resultCount={0} search={{ value: q, onChange: setQ }}>
          <button
            type="button"
            onClick={() => {
              onEnum('immediate');
            }}
          >
            Enum
          </button>
        </FilterBar>
      );
    }
    renderWithProviders(<Probe />);
    screen.getByRole('button', { name: 'Enum' }).click();
    expect(onEnum).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'cache' } });
    expect(onEnum).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(300);
    vi.useRealTimers();
  });
});
