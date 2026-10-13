import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { HiddenDefaultWidget } from './HiddenDefaultWidget';

describe('HiddenDefaultWidget', () => {
  it('names the hidden widget and states it is restorable, not deleted', () => {
    renderWithProviders(<HiddenDefaultWidget title="Hit rate" onRestore={() => {}} restorePending={false} />);
    expect(screen.getByText(/Hidden default/)).toBeInTheDocument();
    expect(screen.getByText(/Hit rate/)).toBeInTheDocument();
    expect(screen.getByText(/Hidden, not deleted/)).toBeInTheDocument();
  });

  it('calls onRestore when the Restore action is clicked', async () => {
    const user = userEvent.setup();
    const onRestore = vi.fn();
    renderWithProviders(<HiddenDefaultWidget title="Hit rate" onRestore={onRestore} restorePending={false} />);

    await user.click(screen.getByRole('button', { name: 'Restore' }));
    expect(onRestore).toHaveBeenCalledTimes(1);
  });

  it('disables Restore while a restore is already in flight', () => {
    renderWithProviders(<HiddenDefaultWidget title="Hit rate" onRestore={() => {}} restorePending />);
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled();
  });
});
