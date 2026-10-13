import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Alert } from './Alert';

describe('Alert', () => {
  it('renders a title and body', () => {
    renderWithProviders(
      <Alert severity="err" title="Could not load">
        The server timed out.
      </Alert>,
    );

    expect(screen.getByText('Could not load')).not.toBeNull();
    expect(screen.getByText('The server timed out.')).not.toBeNull();
  });

  it('is announced (role="alert") only for the err severity', () => {
    const { rerender } = renderWithProviders(<Alert severity="err">Failed.</Alert>);
    expect(screen.getByRole('alert')).not.toBeNull();

    rerender(<Alert severity="info">Heads up.</Alert>);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByRole('status')).not.toBeNull();
  });

  it.each(['info', 'warn', 'err', 'ok', 'neutral'] as const)(
    'renders a default icon for the %s severity',
    (severity) => {
      const { container } = renderWithProviders(<Alert severity={severity}>Text</Alert>);
      expect(container.querySelector('svg')).not.toBeNull();
    },
  );

  it('lets a caller override the default icon', () => {
    renderWithProviders(
      <Alert severity="info" icon={<span data-testid="custom-icon" />}>
        Text
      </Alert>,
    );

    expect(screen.getByTestId('custom-icon')).not.toBeNull();
  });

  it('renders the action slot', () => {
    const onRetry = vi.fn();
    renderWithProviders(
      <Alert severity="err" action={<button onClick={onRetry}>Retry</button>}>
        Failed.
      </Alert>,
    );

    screen.getByRole('button', { name: 'Retry' }).click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('renders full-width without throwing when used as a banner', () => {
    renderWithProviders(
      <Alert severity="info" fullWidth>
        3 of 12 instances unavailable.
      </Alert>,
    );

    expect(screen.getByText('3 of 12 instances unavailable.')).not.toBeNull();
  });
});
