import { fireEvent } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { createAdminRepository } from '../adminRepository';

import { AdminShell } from './AdminShell';
import type { AdminTab } from './AdminShell.types';

const repository = createAdminRepository(createCpClient('/api'));

function renderShell(tab: AdminTab) {
  return renderWithProviders(<AdminShell tab={tab} repository={repository} />, {
    // The Groups tab gates its own controls; give the shell tests an
    // identity that holds every verb those gates map onto.
    // The ratified admin identity (`D18` 🔒): the Users verbs plus the five
    // `group.*` ones the Groups tab gates on (`FE-D47`).
    permissions: [
      'user.view',
      'user.create',
      'user.deactivate',
      'role.assign',
      'group.view',
      'group.create',
      'group.edit',
      'group.deactivate',
      'group.member.manage',
    ],
  });
}

describe('AdminShell', () => {
  it('renders the title and the not-in-nav badges', () => {
    renderShell('users');

    expect(screen.getByRole('heading', { name: 'Admin' })).not.toBeNull();
    expect(screen.getByText('Later increment')).not.toBeNull();
    expect(screen.getByText('Not in v1 nav')).not.toBeNull();
  });

  it('renders no catalog editor and keeps the honesty banner collapsed by default', () => {
    renderShell('users');

    expect(screen.queryByText(/catalog editor/i)).toBeNull();
    expect(screen.getByRole('button', { name: /findings/i }).getAttribute('aria-expanded')).toBe(
      'false',
    );
  });

  it('states the catalog status once Findings is opened, including that there is deliberately no catalog editor', () => {
    renderShell('users');

    fireEvent.click(screen.getByRole('button', { name: /findings/i }));

    expect(
      screen.getByText('The model is locked. The catalog and identity are not.'),
    ).not.toBeNull();
    expect(screen.getByText(/deliberately no catalog editor/)).not.toBeNull();
  });

  it('renders Roles | Assignments | Access check | Groups | Users as route links', () => {
    renderShell('users');

    expect(screen.getByRole('tab', { name: 'Roles' }).getAttribute('href')).toBe('/admin/roles');
    expect(screen.getByRole('tab', { name: 'Assignments' }).getAttribute('href')).toBe(
      '/admin/assignments',
    );
    expect(screen.getByRole('tab', { name: 'Access check' }).getAttribute('href')).toBe(
      '/admin/access',
    );
    expect(screen.getByRole('tab', { name: 'Groups' }).getAttribute('href')).toBe('/admin/groups');
    expect(screen.getByRole('tab', { name: 'Users' }).getAttribute('href')).toBe('/admin/users');
  });

  it('keeps the tab order Roles → Assignments → Access check → Groups → Users', () => {
    renderShell('users');

    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'Roles',
      'Assignments',
      'Access check',
      'Groups',
      'Users',
    ]);
  });

  it('renders the groups tab as its real view, not a placeholder', async () => {
    renderShell('groups');

    expect(await screen.findByRole('heading', { name: 'Groups' })).not.toBeNull();
    expect(
      screen.getByText('Manage user groups and assign role-based access collectively.'),
    ).not.toBeNull();
  });

  it('renders the roles tab as its real view (FE-091), not a placeholder', async () => {
    renderShell('roles');

    expect(await screen.findByRole('heading', { name: 'Role definitions' })).not.toBeNull();
    expect(screen.queryByText('Roles — not yet built')).toBeNull();
  });

  it('renders the assignments tab as its real view (FE-092), not a placeholder', async () => {
    renderShell('assignments');

    expect(await screen.findByRole('heading', { name: 'Assignments' })).not.toBeNull();
    expect(screen.queryByText('Assignments — not yet built')).toBeNull();
  });

  it('renders the access tab as its real view (FE-093), not a placeholder', async () => {
    renderShell('access');

    expect(await screen.findByRole('heading', { name: 'Effective permissions' })).not.toBeNull();
    expect(screen.queryByText('Access check — not yet built')).toBeNull();
  });

  it('renders the users tab as its real view (FE-094), not a placeholder', async () => {
    renderShell('users');

    expect(await screen.findByRole('heading', { name: 'Users' })).not.toBeNull();
    expect(screen.queryByText('Users — not yet built')).toBeNull();
  });
});
