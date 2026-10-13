import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../test/renderWithProviders';

import { FirstRun } from './FirstRun';

describe('FirstRun', () => {
  it('renders the package prerequisite and counts as "—", never "0"', () => {
    renderWithProviders(<FirstRun superAdmin={true} />);

    expect(screen.getByText('Load a Solution Package to begin')).not.toBeNull();
    expect(screen.queryByText('0')).toBeNull();
    expect(screen.getAllByText('—')).toHaveLength(3);
  });

  it('shows the load-package action for a super-admin', () => {
    renderWithProviders(<FirstRun superAdmin={true} />);

    expect(screen.getByRole('button', { name: 'Load a package' })).not.toBeNull();
    expect(screen.queryByText(/ask your administrator/i)).toBeNull();
  });

  it('replaces the action with an ask-your-administrator alert for a non-super-admin', () => {
    renderWithProviders(<FirstRun superAdmin={false} />);

    expect(screen.queryByRole('button', { name: 'Load a package' })).toBeNull();
    expect(
      screen.getByText(
        'Loading a package is a Solace super-admin action. Ask your administrator to publish and load one.',
      ),
    ).not.toBeNull();
  });
});
