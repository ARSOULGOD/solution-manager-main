/**
 * Instance grid column tests (integration plan 007, 2026-10-09). Rewritten
 * against the real `Instance` shape — the old vocabulary-coverage suite
 * exercised `driver`/`locus`/`health`/`version`/`config` cell renderers,
 * none of which exist on this grid anymore (see `columns.tsx`'s header for
 * why: all five required an N+1 fetch per row and already have a home on
 * the instance detail tabs).
 */
import { describe, expect, it } from 'vitest';

import type { Instance } from '@shared/api/contracts/instances';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { buildInstanceColumns } from './columns';

function baseRow(overrides: Partial<Instance> = {}): Instance {
  return {
    stateVersion: 1,
    instanceId: 'row-1',
    name: 'row-1',
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [
      { placementId: 'p1', targetId: 't1', containerName: 'row-1', probeStrength: 'readiness' },
    ],
    ...overrides,
  };
}

const ENV_NAMES = new Map([['env-1', 'DEV']]);
const columns = buildInstanceColumns('cache', ENV_NAMES);
function column(field: string) {
  const found = columns.find((c) => c.field === field);
  if (found?.renderCell === undefined) {
    throw new Error(`no renderCell for column "${field}"`);
  }
  return found.renderCell;
}

describe('instance columns', () => {
  it('name: renders as a link to the designer tab', () => {
    renderWithProviders(<>{column('name')(baseRow({ name: 'ledger-01' }))}</>);
    const link = screen.getByText('ledger-01');
    expect(link.getAttribute('href')).toBe('/solutions/cache/instances/row-1/designer');
  });

  it('env: renders the resolved environment name as a chip', () => {
    renderWithProviders(<>{column('env')(baseRow({ environmentId: 'env-1' }))}</>);
    expect(screen.getByText('DEV')).not.toBeNull();
  });

  it('env: renders a dash when the environment name has not resolved', () => {
    renderWithProviders(<>{column('env')(baseRow({ environmentId: 'env-unknown' }))}</>);
    expect(screen.getByText('—')).not.toBeNull();
  });

  it('renders the package version as a neutral, monospace chip', () => {
    renderWithProviders(<>{column('packageVersion')(baseRow({ declaredPackageVersion: 6 }))}</>);

    const chip = screen.getByText('6');
    expect(chip).not.toBeNull();
    // NOT a semantic tone — `ok`/`warn`/`err` mean health here, and a
    // version is not a health state.
    expect(getComputedStyle(chip).fontFamily).toBe('monospace');
  });

  it('sorts and exports the package version as a string, whatever its type', () => {
    const def = columns.find((c) => c.field === 'packageVersion');

    expect(def?.valueGetter?.(baseRow({ declaredPackageVersion: 6 }))).toBe('6');
    expect(def?.valueGetter?.(baseRow({ declaredPackageVersion: 'dev-2026.08.17' }))).toBe(
      'dev-2026.08.17',
    );
  });

  it('renders the placement count', () => {
    renderWithProviders(
      <>
        {column('placements')(
          baseRow({
            placements: [
              { placementId: 'p1', targetId: 't1', containerName: 'a', probeStrength: 'readiness' },
              { placementId: 'p2', targetId: 't2', containerName: 'b', probeStrength: 'readiness' },
            ],
          }),
        )}
      </>,
    );
    expect(screen.getByText('2')).not.toBeNull();
  });

  it('is exactly name, env, package version, placements — no observed-state columns', () => {
    expect(columns.map((c) => c.field)).toEqual(['name', 'env', 'packageVersion', 'placements']);
  });
});
