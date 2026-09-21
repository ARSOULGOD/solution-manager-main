import { fireEvent } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import { createAdminRepository } from '../adminRepository';

import { AdminShell } from './AdminShell';
import { ADMIN_TABS } from './AdminShell.types';

const repository = createAdminRepository(createCpClient('/api'));
const modes = ['light', 'dark'] as const;

async function settle(tab: (typeof ADMIN_TABS)[number]) {
  if (tab === 'roles') {
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Role definitions' })).not.toBeNull();
    });
  }
  if (tab === 'assignments') {
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Assignments' })).not.toBeNull();
    });
  }
  if (tab === 'access') {
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Effective permissions' })).not.toBeNull();
    });
  }
  if (tab === 'groups') {
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Groups' })).not.toBeNull();
    });
  }
  if (tab === 'users') {
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Users' })).not.toBeNull();
    });
  }
}

describe('AdminShell a11y', () => {
  it.each(modes)('%s mode, findings collapsed, has no violations', async (mode) => {
    const { container } = renderWithProviders(<AdminShell tab="roles" repository={repository} />, {
      mode,
    });
    await settle('roles');
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('%s mode, findings open, has no violations', async (mode) => {
    const { container, getByRole } = renderWithProviders(
      <AdminShell tab="roles" repository={repository} />,
      { mode },
    );
    await settle('roles');
    fireEvent.click(getByRole('button', { name: /findings/i }));
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(ADMIN_TABS)('%s tab has no violations', async (tab) => {
    const { container } = renderWithProviders(<AdminShell tab={tab} repository={repository} />);
    await settle(tab);
    expect(await axe(container)).toHaveNoViolations();
  });
});
