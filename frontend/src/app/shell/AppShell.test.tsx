/**
 * AppShell (task 036, task 106) tested against its own small route tree —
 * real `router.tsx` integration is a later task's job (see the file
 * header). `AuthProvider`'s DEV mock (task 030) resolves asynchronously
 * even though it never touches the network, so identity-dependent
 * assertions wait for it — same pattern `renderWithProviders`'s own
 * header documents.
 *
 * The responsive describe block below (task 106, `FE-D29`) uses
 * `setViewportWidth` (`../../test/matchMedia`) — jsdom has no real
 * `matchMedia`, so every other test in this file runs at the polyfill's
 * default desktop width and never sees the narrow branches at all.
 */
import userEvent from '@testing-library/user-event';
import { Link, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import type { InstancesRepository } from '@features/inventory/instances';
import { createSolutionsRepository } from '@features/inventory/solutions';
import { createCpClient } from '@shared/api/cpClient';
import { useUiStore } from '@shared/state/uiStore';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../test/matchMedia';
import { assertReflow } from '../../test/reflow';
import { renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { AppShell } from './AppShell';

function TestPage({ title, to }: { title: string; to?: string }) {
  return (
    <div>
      <h1>{title}</h1>
      {to !== undefined && <Link to={to}>Go to {to}</Link>}
    </div>
  );
}

// The same base path `renderWithProviders` gives its own client, so the
// MSW `*/cp/solutions` handler (task 033 fixtures) answers the drawer's
// catalog query exactly as it answers the catalog screen's.
const solutionsRepository = createSolutionsRepository(createCpClient('/api'));

function renderShell(route = '/', instancesRepository?: InstancesRepository) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <AppShell
            solutionsRepository={solutionsRepository}
            {...(instancesRepository === undefined ? {} : { instancesRepository })}
          />
        }
      >
        <Route index element={<TestPage title="Home" to="/other" />} />
        <Route path="other" element={<TestPage title="Other page" />} />
        <Route path="solutions/:solutionId/instances" element={<TestPage title="Instances" />} />
        <Route
          path="solutions/:solutionId/instances/:instanceId/:category"
          element={<TestPage title="Instance category" />}
        />
      </Route>
    </Routes>,
    { route },
  );
}

describe('AppShell', () => {
  it('renders the skip link, drawer navigation, and app bar breadcrumb', () => {
    renderShell();

    expect(screen.getByText('Skip to main content')).not.toBeNull();
    expect(screen.getByRole('navigation', { name: 'Solution Manager' })).not.toBeNull();
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).not.toBeNull();
  });

  it('focuses <main> on first render and again after navigating', async () => {
    const user = userEvent.setup();
    renderShell();

    const main = screen.getByRole('main');
    await waitFor(() => {
      expect(main).toHaveFocus();
    });

    await user.click(screen.getByRole('link', { name: 'Go to /other' }));

    expect(await screen.findByText('Other page')).not.toBeNull();
    await waitFor(() => {
      expect(screen.getByRole('main')).toHaveFocus();
    });
  });

  it('lists the loaded solutions in the drawer, counted by solution and not by package', async () => {
    renderShell();

    // Fixtures hold two packages, both `cache` — a signed release and a
    // dev-mode nightly — so the drawer's solution count is 1, not 2.
    expect(await screen.findByRole('link', { name: 'Solutions (1)' })).not.toBeNull();
    // `console.yaml`'s `Solution` carries no per-solution instance count
    // (integration plan 005 Task 2) — `toSidebarSolutions()` flags a `0`
    // rather than inventing a number; see that file's header and
    // `docs/plans/integration/reports/005-task-report.md`'s open decisions.
    expect(screen.getByRole('link', { name: 'Cache Engine (0 instances)' })).not.toBeNull();
    // `UI-D19`'s dev-mode-package exclusion from the drawer was keyed on a
    // `devMode` flag that does not exist on `console.yaml`'s `Solution` —
    // v1 loads no signed packages at all, so there is no signed/unsigned
    // split left to filter on (plan 005 Task 2's open decision 1: `UI-D19`
    // is effectively superseded, pending an explicit ruling). Honestly,
    // every loaded package is now advertised.
    expect(screen.getByRole('link', { name: /nightly/i })).not.toBeNull();
  });

  it('uses the catalog display name instead of the solution id in the breadcrumb', async () => {
    renderShell('/solutions/cache/instances');

    const solutionCrumb = await screen.findByRole('link', { name: 'Cache Engine' });
    expect(solutionCrumb).toHaveAttribute('href', '/solutions/cache/instances');
    expect(screen.queryByRole('link', { name: 'cache' })).toBeNull();
  });

  it('uses the instance name instead of the instance id in the breadcrumb', async () => {
    const instancesRepository = {
      get: () => Promise.resolve({ name: 'cache-prod-readable' }),
    } as unknown as InstancesRepository;

    renderShell('/solutions/cache/instances/cache-prod-01/designer', instancesRepository);

    const instanceCrumb = await screen.findByRole('link', { name: 'cache-prod-readable' });
    expect(instanceCrumb).toHaveAttribute(
      'href',
      '/solutions/cache/instances/cache-prod-01/designer',
    );
    expect(screen.queryByRole('link', { name: 'cache-prod-01' })).toBeNull();
  });

  it('shows no count at all while the catalog is unknown — never a fabricated "(0)"', () => {
    renderWithProviders(
      <Routes>
        <Route path="/" element={<AppShell />}>
          <Route index element={<TestPage title="Home" />} />
        </Route>
      </Routes>,
    );

    expect(screen.getByRole('link', { name: 'Solutions' })).not.toBeNull();
    expect(screen.queryByRole('link', { name: /Solutions \(/ })).toBeNull();
  });

  it('renders no demo-state or scenario selector', () => {
    renderShell();

    expect(screen.queryByText(/demo state/i)).toBeNull();
    expect(screen.queryByLabelText(/scenario/i)).toBeNull();
  });

  it('renders no bell / paging affordance (the toast region is acknowledgement, not alerting)', () => {
    renderShell();

    // Toaster's aria-live="Notifications" region is the one exception —
    // it's mutation acknowledgement (task 021, design §9), never a bell
    // implying something pages someone (design §20).
    expect(screen.queryByRole('button', { name: /bell|page|alert/i })).toBeNull();
    expect(screen.queryByLabelText(/bell|paging/i)).toBeNull();
  });

  it('renders the organization as non-interactive text, never a switcher', async () => {
    renderShell();

    const org = await screen.findByText('ACME');
    expect(org.closest('button')).toBeNull();
    expect(org.closest('a')).toBeNull();
    expect(org.closest('[title]')?.getAttribute('title')).toMatch(/not switchable/i);
  });

  it('account menu shows identity, a permission count, and sign out; Admin appears DEV-only', async () => {
    const user = userEvent.setup();
    renderShell();

    const trigger = await screen.findByRole('button', { name: 'Account' });
    await user.click(trigger);

    expect(screen.getByText('devon.ramirez')).not.toBeNull();
    // Not a hardcoded count — the catalog's size has already drifted once
    // (see localSessionAdapter.ts's superAdmin derivation comment); the
    // real Identity wire has no role name to assert against at all.
    expect(screen.getByText(/^\d+ permissions?$/)).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Admin' })).not.toBeNull();
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).not.toBeNull();
  });

  it('support menu shows the UI version and a correlation id with a copy affordance', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('button', { name: 'Support and version' }));

    expect(screen.getByText('UI version')).not.toBeNull();
    expect(screen.getByText('Correlation id')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Copy id' })).not.toBeNull();
  });
});

describe('AppShell responsive (task 106, FE-D29)', () => {
  it('the drawer is docked by default at lg and up, expanded', () => {
    setViewportWidth(1280);
    renderShell();

    expect(screen.getByRole('navigation', { name: 'Solution Manager' })).not.toBeNull();
    // The rail's own control, offering the only move available from here.
    expect(screen.getByRole('button', { name: 'Collapse navigation' })).not.toBeNull();
    // The app bar's hamburger belongs to the overlay drawer, which does
    // not exist at this width — a second toggle here would be a duplicate
    // accessible name for the same navigation.
    expect(screen.queryByRole('button', { name: /Open navigation|Close navigation/ })).toBeNull();
  });

  it('below lg the drawer starts closed, opens via a labelled menu button, and traps and restores focus on Escape', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderShell();

    expect(screen.queryByRole('navigation', { name: 'Solution Manager' })).toBeNull();

    const menuButton = screen.getByRole('button', { name: 'Open navigation' });
    await user.click(menuButton);

    expect(await screen.findByRole('navigation', { name: 'Solution Manager' })).not.toBeNull();

    await user.keyboard('{Escape}');

    await waitFor(() => {
      expect(screen.queryByRole('navigation', { name: 'Solution Manager' })).toBeNull();
    });
    expect(menuButton).toHaveFocus();
  });

  it('closes the overlay drawer on navigation', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('button', { name: 'Open navigation' }));
    await screen.findByRole('navigation', { name: 'Solution Manager' });

    // The drawer is a proper ARIA modal while open — MUI hides the rest of
    // the page from the accessibility tree, so the only reachable link is
    // one inside the drawer itself (`SidebarNav`'s own "Solutions"), same
    // as a real user could only do.
    await user.click(screen.getByRole('link', { name: /Solutions/ }));

    await waitFor(() => {
      expect(screen.queryByRole('navigation', { name: 'Solution Manager' })).toBeNull();
    });
  });

  it('the organization is reachable from the account menu below md — moved, never dropped', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderShell();

    expect(screen.queryByText('ACME')).toBeNull();

    await user.click(await screen.findByRole('button', { name: 'Account' }));

    expect(screen.getByText('ACME')).not.toBeNull();
  });

  it('assertReflow passes on the shell at the 320px reflow floor', async () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderShell();

    await waitFor(() => {
      expect(screen.getByRole('main')).not.toBeNull();
    });

    const root = container.querySelector('[data-app-shell]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });

  it('the open overlay drawer itself does not force horizontal scroll at 320px', async () => {
    setViewportWidth(reflowFloorPx);
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('button', { name: 'Open navigation' }));
    await screen.findByRole('navigation', { name: 'Solution Manager' });

    // The drawer's Paper is portaled to document.body (MUI Modal), not a
    // descendant of the shell root queried above — assert it separately.
    const paper = document.querySelector('.MuiDrawer-paper');
    expect(paper).toBeInstanceOf(HTMLElement);
    if (!(paper instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(paper, reflowFloorPx);
    }).not.toThrow();
  });
});

describe('AppShell desktop nav collapse to an icon rail (task 111)', () => {
  beforeEach(() => {
    localStorage.clear();
    useUiStore.setState({ navCollapsed: false });
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('collapses to a rail and re-expands at lg and up; the rail and its toggle are never hidden', async () => {
    setViewportWidth(1280);
    const user = userEvent.setup();
    renderShell();

    expect(screen.getByRole('navigation', { name: 'Solution Manager' })).not.toBeNull();

    await user.click(screen.getByRole('button', { name: 'Collapse navigation' }));

    // The rail stays: collapsing hides the labels, never the navigation.
    // Every destination is still one click away and still named — the
    // labels leave the layout, not the accessibility tree.
    const nav = screen.getByRole('navigation', { name: 'Solution Manager' });
    expect(nav.getAttribute('data-collapsed')).toBe('true');
    expect(screen.getByRole('link', { name: 'Home' })).not.toBeNull();
    // See the flagged `(0 instances)` note above (plan 005 Task 2).
    expect(await screen.findByRole('link', { name: 'Cache Engine (0 instances)' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Expand navigation' })).not.toBeNull();

    await user.click(screen.getByRole('button', { name: 'Expand navigation' }));

    expect(
      screen.getByRole('navigation', { name: 'Solution Manager' }).getAttribute('data-collapsed'),
    ).toBeNull();
  });

  it('the rail reserves the narrower docked width, so main reflows into what it gives back', async () => {
    setViewportWidth(1280);
    const user = userEvent.setup();
    renderShell();

    const docked = document.querySelector('.MuiDrawer-docked');
    expect(docked).toBeInstanceOf(HTMLElement);
    if (!(docked instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(docked).width).toBe('var(--drawer-w)');

    await user.click(screen.getByRole('button', { name: 'Collapse navigation' }));

    expect(getComputedStyle(docked).width).toBe('var(--drawer-w-rail)');
  });

  it('persists the collapsed preference so it survives reload', async () => {
    setViewportWidth(1280);
    const user = userEvent.setup();
    const first = renderShell();

    await user.click(screen.getByRole('button', { name: 'Collapse navigation' }));

    const stored = localStorage.getItem('sm-ui-store');
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored ?? '{}') as { state: { navCollapsed: boolean } };
    expect(parsed.state.navCollapsed).toBe(true);

    first.unmount();

    // A fresh mount (simulating reload) reads the same persisted store.
    renderShell();
    expect(
      screen.getByRole('navigation', { name: 'Solution Manager' }).getAttribute('data-collapsed'),
    ).toBe('true');
  });

  it('the collapsed docked drawer is not a modal — no backdrop, no focus trap', async () => {
    setViewportWidth(1280);
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('button', { name: 'Collapse navigation' }));

    expect(document.querySelector('.MuiBackdrop-root')).toBeNull();
    expect(screen.getByRole('button', { name: 'Expand navigation' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  it('the docked wrapper reserves the drawer width, so the fixed-position paper cannot overlap main content', () => {
    setViewportWidth(1280);
    renderShell();

    // MUI's Drawer Paper is `position: fixed` for every variant (including
    // `permanent`) — only the `.MuiDrawer-docked` wrapper div participates
    // in the flex layout. If that wrapper has no width of its own, it
    // collapses to 0 (its only child is out of flow), the main-content
    // flex sibling slides left to fill the gap, and the fixed-position
    // Paper then floats visually on top of it — a real-browser overlap
    // invisible to `getBoundingClientRect` in jsdom, so this asserts
    // computed CSS width instead of a laid-out rect.
    const docked = document.querySelector('.MuiDrawer-docked');
    const paper = document.querySelector('.MuiDrawer-paper');
    expect(docked).toBeInstanceOf(HTMLElement);
    expect(paper).toBeInstanceOf(HTMLElement);
    if (!(docked instanceof HTMLElement) || !(paper instanceof HTMLElement)) {
      return;
    }
    expect(getComputedStyle(docked).width).not.toBe('');
    expect(getComputedStyle(docked).width).toBe(getComputedStyle(paper).width);
  });
});
