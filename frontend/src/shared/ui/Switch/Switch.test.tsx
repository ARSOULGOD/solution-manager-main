import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Switch } from './Switch';

describe('Switch', () => {
  it('announces its label and current state', () => {
    renderWithProviders(<Switch label="Auto-restart" checked onChange={() => {}} />);

    const toggle = screen.getByRole('switch', { name: 'Auto-restart' });
    expect(toggle).toBeChecked();
  });

  it('reports the new state when toggled', () => {
    const onChange = vi.fn();
    renderWithProviders(<Switch label="Auto-restart" checked={false} onChange={onChange} />);

    fireEvent.click(screen.getByRole('switch', { name: 'Auto-restart' }));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('wires helper text to the control via aria-describedby', () => {
    renderWithProviders(
      <Switch
        label="Auto-restart"
        checked
        onChange={() => {}}
        helperText="Restarts a not-responding replica automatically."
      />,
    );

    const toggle = screen.getByRole('switch', { name: 'Auto-restart' });
    const describedBy = toggle.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Restarts a not-responding replica automatically.',
    );
  });

  it('swaps helper text for the error message and marks the control invalid', () => {
    renderWithProviders(
      <Switch
        label="Auto-restart"
        checked
        onChange={() => {}}
        helperText="Restarts automatically."
        error="Cannot be enabled without a health check configured."
      />,
    );

    const toggle = screen.getByRole('switch', { name: 'Auto-restart' });
    expect(toggle.getAttribute('aria-invalid')).toBe('true');
    const describedBy = toggle.getAttribute('aria-describedby');
    expect(document.getElementById(describedBy ?? '')?.textContent).toBe(
      'Cannot be enabled without a health check configured.',
    );
  });
});
