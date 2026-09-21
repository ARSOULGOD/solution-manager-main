import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { describe, expect, it } from 'vitest';

import { PERMISSION_CATALOG } from '@shared/authz/catalog';

import { renderWithProviders, screen } from '../test/renderWithProviders';

import { Forbidden } from './Forbidden';

describe('Forbidden', () => {
  it('shows the server correlation id and names no permission from the catalog', () => {
    renderWithProviders(<Forbidden correlationId="corr-403-a1b2" />);

    expect(screen.getByText(/corr-403-a1b2/)).not.toBeNull();
    for (const permission of PERMISSION_CATALOG) {
      expect(screen.queryByText(permission.id, { exact: false })).toBeNull();
    }
  });

  it('states that the action is not permitted, not why', () => {
    renderWithProviders(<Forbidden correlationId="corr-403-a1b2" />);

    expect(screen.getByText(/you don.t have permission to do this/i)).not.toBeNull();
    expect(screen.getByText(/isn.t permitted for your account/i)).not.toBeNull();
  });

  it('offers a route back to Home', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>Home page marker</div>} />
        <Route path="/blocked" element={<Forbidden correlationId="corr-403-a1b2" />} />
      </Routes>,
      { route: '/blocked' },
    );

    await user.click(screen.getByRole('button', { name: 'Back to Home' }));

    expect(await screen.findByText('Home page marker')).not.toBeNull();
  });
});
