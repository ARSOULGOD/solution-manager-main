import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Breadcrumbs } from './Breadcrumbs';

describe('Breadcrumbs', () => {
  it('derives the trail from the route and does not link the current page', () => {
    renderWithProviders(<Breadcrumbs labels={{ solution: 'Cache', instance: 'cache-prod' }} />, {
      route: '/solutions/cache/instances/cache-prod/designer',
    });

    const nav = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(nav).not.toBeNull();
    expect(screen.getByRole('link', { name: 'Home' })).not.toBeNull();
    expect(screen.getByRole('link', { name: 'Solutions' })).not.toBeNull();
    expect(screen.queryByRole('link', { name: 'Designer' })).toBeNull();
    expect(screen.getByText('Designer')).not.toBeNull();
  });
});

describe('Breadcrumbs responsive (task 106, FE-D29)', () => {
  it('collapses to "… / current" below md, keeping the current page visible and un-linked', () => {
    setViewportWidth(320);
    renderWithProviders(<Breadcrumbs labels={{ solution: 'Cache', instance: 'cache-prod' }} />, {
      route: '/solutions/cache/instances/cache-prod/designer',
    });

    expect(screen.getByText('Designer')).not.toBeNull();
    expect(screen.queryByRole('link', { name: 'Designer' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Home' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Solutions' })).toBeNull();
  });

  it('reaches every elided ancestor from the "…" menu', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderWithProviders(<Breadcrumbs labels={{ solution: 'Cache', instance: 'cache-prod' }} />, {
      route: '/solutions/cache/instances/cache-prod/designer',
    });

    await user.click(screen.getByRole('button', { name: 'Show earlier pages' }));

    expect(screen.getByRole('menuitem', { name: 'Home' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Solutions' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Cache' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Instances' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'cache-prod' })).not.toBeNull();
  });

  it('shows the full un-collapsed trail at md and up', () => {
    setViewportWidth(905);
    renderWithProviders(<Breadcrumbs labels={{ solution: 'Cache', instance: 'cache-prod' }} />, {
      route: '/solutions/cache/instances/cache-prod/designer',
    });

    expect(screen.getByRole('link', { name: 'Home' })).not.toBeNull();
    expect(screen.getByRole('link', { name: 'Solutions' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Show earlier pages' })).toBeNull();
  });

  it('renders no "…" trigger on Home, where there is nothing to elide', () => {
    setViewportWidth(320);
    renderWithProviders(<Breadcrumbs />, { route: '/' });

    expect(screen.getByText('Home')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Show earlier pages' })).toBeNull();
  });
});
