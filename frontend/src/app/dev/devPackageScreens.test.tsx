/**
 * The screens' half of the click-through: a loaded package appears on the
 * catalog as a real card, and its Open action navigates to its own
 * instance list. `InstanceShell` (task 044) renders the real `DesignerPage`
 * (task 123) for the `designer` category — the projected view model itself
 * (sections, fields, from the dropped package) is `devPackageFlow.test.ts`'s
 * job at the repository boundary; this file stays at the catalog/navigation
 * layer.
 */
import { useLocation } from 'react-router';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';

import { SolutionsPage, createSolutionsRepository } from '@features/inventory/solutions';
import { createCpClient } from '@shared/api/cpClient';
import { server } from '@shared/testdoubles/server';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../test/renderWithProviders';

import { devPackageHandlers } from './devPackageHandlers';
import { devPackageStore } from './devPackageStore';
import type { LoadedPackage } from './parsePackage';
import { TEST_PACKAGE } from './testPackage';

const pkg = TEST_PACKAGE as unknown as LoadedPackage;
const repository = createSolutionsRepository(createCpClient('/api'));

function Location() {
  return <output data-testid="pathname">{useLocation().pathname}</output>;
}

/** The Open button inside the catalog card whose heading is `name`. */
function openButtonOfCardNamed(name: string): HTMLButtonElement {
  const card = screen.getByText(name).closest('[data-surface]');
  expect(card).not.toBeNull();
  const open = Array.from(card?.querySelectorAll('button') ?? []).find(
    (button) => button.textContent === 'Open',
  );
  expect(open).toBeDefined();
  return open as HTMLButtonElement;
}

beforeEach(() => {
  devPackageStore.clear();
  server.use(...devPackageHandlers);
});

afterEach(() => {
  devPackageStore.clear();
});

describe('a loaded package on the catalog screen', () => {
  it('renders as a card beside the packages that were already there', async () => {
    devPackageStore.register(pkg);

    renderWithProviders(<SolutionsPage repository={repository} />);

    await waitFor(() => {
      // The derived display name (`deriveName`), not the raw solution id.
      expect(screen.getByText('Widget Mesh')).not.toBeNull();
    });
    expect(screen.getByText('Cache Engine')).not.toBeNull();
    // The derived one-line descriptor is the declared component types, so
    // it reads the same as the component chip below it — hence `getAllBy`.
    expect(screen.getAllByText('widget-connector').length).toBeGreaterThan(0);
  });

  it('opens into its own instance list', async () => {
    devPackageStore.register(pkg);

    renderWithProviders(
      <>
        <SolutionsPage repository={repository} />
        <Location />
      </>,
      { route: '/solutions' },
    );

    await waitFor(() => {
      expect(screen.getByText('Widget Mesh')).not.toBeNull();
    });

    // The card's own Open button — found via the card that carries the
    // package's name, so this cannot accidentally click Cache's. The card
    // is located by `data-surface`, which `Paper` emits; `SolutionCard`'s
    // own `data-solution-card` never reaches the DOM, because `Paper`
    // (`shared/ui`) does not forward unknown props. Pre-existing, and not
    // this task's to change.
    const open = openButtonOfCardNamed('Widget Mesh');
    fireEvent.click(open);

    // The solution id the package declares — the same key the instance
    // list, the instance and the Designer are all served under.
    await waitFor(() => {
      expect(screen.getByTestId('pathname').textContent).toBe('/solutions/widget-mesh/instances');
    });
  });

  it('an UNSIGNED package is admitted to the catalog and openable in a dev console (UI-D19)', async () => {
    // `SolutionCard` blocks Open for `devMode` only when the console
    // itself is NOT running in development mode — the same admission rule
    // `LoadPackageDialog`'s `isAdmitted()` already applies to loading the
    // package in the first place. Vitest always runs with
    // `import.meta.env.DEV = true`, so this is exactly the dev-console
    // path the unsigned admission exists for: load it, then open it.
    devPackageStore.register({ ...pkg, provenance: { author: 'acme' } });

    renderWithProviders(
      <>
        <SolutionsPage repository={repository} />
        <Location />
      </>,
      { route: '/solutions' },
    );

    await waitFor(() => {
      expect(screen.getByText('Widget Mesh')).not.toBeNull();
    });
    expect(screen.getAllByText('Unsigned · dev-mode').length).toBeGreaterThan(0);
    const open = openButtonOfCardNamed('Widget Mesh');
    expect(open.hasAttribute('disabled')).toBe(false);

    fireEvent.click(open);

    await waitFor(() => {
      expect(screen.getByTestId('pathname').textContent).toBe(
        '/solutions/widget-mesh/instances',
      );
    });
  });
});
