import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { Button } from '../Button';

import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  it('names why it is empty and what to do, and is not an error path', () => {
    renderWithProviders(
      <EmptyState
        title="No instances declared yet"
        body="Declare an instance, apply its config, then restart. It stays unobservable until after that first restart — an early missing state is expected, not a fault."
        action={<Button>Declare</Button>}
      />,
    );
    expect(screen.getByRole('heading', { name: 'No instances declared yet' })).not.toBeNull();
    expect(screen.getByText(/Declare an instance/)).not.toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
