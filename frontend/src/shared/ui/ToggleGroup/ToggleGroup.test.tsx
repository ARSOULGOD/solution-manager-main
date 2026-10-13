import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ToggleGroup } from './ToggleGroup';

describe('ToggleGroup', () => {
  it('exposes role=group and aria-pressed on the selected option', async () => {
    const user = userEvent.setup();
    function Probe() {
      const [value, setValue] = useState('all');
      return (
        <ToggleGroup
          aria-label="Environment"
          value={value}
          onChange={setValue}
          options={[
            { value: 'all', label: 'All' },
            { value: 'prod', label: 'PROD' },
          ]}
        />
      );
    }
    renderWithProviders(<Probe />);
    expect(screen.getByRole('group', { name: 'Environment' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'All' }).getAttribute('aria-pressed')).toBe('true');
    await user.click(screen.getByRole('button', { name: 'PROD' }));
    expect(screen.getByRole('button', { name: 'PROD' }).getAttribute('aria-pressed')).toBe('true');
  });

  it('disables every option when disabled (task 078 — a caller gating the whole group, not one option)', () => {
    renderWithProviders(
      <ToggleGroup
        aria-label="Series by"
        value="instance"
        onChange={() => {}}
        options={[
          { value: 'instance', label: 'Instance' },
          { value: 'replica', label: 'Replica' },
        ]}
        disabled
      />,
    );
    expect(screen.getByRole('button', { name: 'Instance' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Replica' })).toBeDisabled();
  });
});
