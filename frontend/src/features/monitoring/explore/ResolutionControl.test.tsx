import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';

import { ResolutionControl, type Resolution } from './ResolutionControl';

function Probe({
  forceRawAdvertised = true,
  initial = 'auto' as const,
  onChange,
}: {
  forceRawAdvertised?: boolean;
  initial?: Resolution;
  onChange?: (value: Resolution) => void;
}) {
  const [value, setValue] = useState<Resolution>(initial);
  return (
    <ResolutionControl
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange?.(next);
      }}
      forceRawAdvertised={forceRawAdvertised}
    />
  );
}

describe('ResolutionControl', () => {
  it('offers only Auto when features.forceRaw is not advertised', () => {
    renderWithProviders(<Probe forceRawAdvertised={false} />);
    expect(screen.getByRole('button', { name: 'Auto' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Raw' })).toBeNull();
  });

  it('offers Raw when features.forceRaw is advertised', () => {
    renderWithProviders(<Probe forceRawAdvertised />);
    expect(screen.getByRole('button', { name: 'Raw' })).not.toBeNull();
  });

  it('clicking Raw opens a high-consequence confirm and does not call onChange yet', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Probe forceRawAdvertised onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Raw' }));
    expect(screen.getByRole('dialog', { name: 'Switch to Raw resolution?' })).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('confirming the dialog commits the switch to Raw', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Probe forceRawAdvertised onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Raw' }));
    await user.click(screen.getByRole('button', { name: 'Switch to Raw' }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith('raw');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Raw' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('cancelling the dialog leaves the resolution at Auto and never calls onChange', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Probe forceRawAdvertised onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Raw' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Auto' })).toHaveAttribute('aria-pressed', 'true');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('switching back to Auto commits immediately, with no confirm', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Probe forceRawAdvertised initial="raw" onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: 'Auto' }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith('auto');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('disabled disables every option', () => {
    renderWithProviders(
      <ResolutionControl value="auto" onChange={() => {}} forceRawAdvertised disabled />,
    );
    expect(screen.getByRole('button', { name: 'Auto' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Raw' })).toBeDisabled();
  });
});
