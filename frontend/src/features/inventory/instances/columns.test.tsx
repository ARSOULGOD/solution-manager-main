/**
 * Vocabulary-coverage tests for the instance grid's cell renderers (task
 * 041). Exercises every `shared/vocab` entry directly through the SAME
 * render function every row uses — the behavioral proof that `driver`/
 * `locus` are pure data lookups (`vocab[row.driver]`), never a branch on
 * their value (`REQ-005`, `REQ-008`): if a cell special-cased one driver
 * or locus value, some entry here would render the wrong label or none
 * at all.
 */
import { describe, expect, it } from 'vitest';

import type { InstanceRow } from '@shared/testdoubles/data/instances';
import {
  configStateVocab,
  driverVocab,
  healthVocab,
  locusVocab,
  notFoundVocab,
  versionVocab,
} from '@shared/vocab';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { buildInstanceColumns } from './columns';

function baseRow(overrides: Partial<InstanceRow> = {}): InstanceRow {
  return {
    id: 'row-1',
    name: 'row-1',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'DEV',
    org: 'Org',
    subOrg: 'Sub',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [{ name: 'r0', health: 'healthy' }],
    healthRollup: 'healthy',
    version: { state: 'ok', observed: '1.0', declared: '1.0' },
    configState: 'in_sync',
    lastSeen: '1s ago',
    ...overrides,
  };
}

const columns = buildInstanceColumns('cache');
function column(field: string) {
  const found = columns.find((c) => c.field === field);
  if (found?.renderCell === undefined) {
    throw new Error(`no renderCell for column "${field}"`);
  }
  return found.renderCell;
}

describe('instance columns — vocabulary coverage', () => {
  it.each(Object.values(healthVocab))('health: renders the "$label" state distinctly', (entry) => {
    renderWithProviders(
      <>{column('health')(baseRow({ healthRollup: entry.key, replicas: [{ name: 'r0', health: entry.key }] }))}</>,
    );
    expect(screen.getByText(entry.label)).not.toBeNull();
  });

  it.each(Object.values(notFoundVocab))(
    'not-found: renders the "$label" state distinctly, with its alert scope',
    (entry) => {
      renderWithProviders(
        <>{column('health')(baseRow({ healthRollup: 'notfound', notFoundReason: entry.key }))}</>,
      );
      expect(screen.getByText(entry.label)).not.toBeNull();
      if (entry.scope !== undefined) {
        expect(screen.getByText(entry.scope)).not.toBeNull();
      }
    },
  );

  it.each(Object.values(versionVocab))('version: renders the "$label" state distinctly', (entry) => {
    renderWithProviders(
      <>{column('version')(baseRow({ version: { state: entry.key, observed: '1.0', declared: '1.0' } }))}</>,
    );
    expect(screen.getByText(entry.label)).not.toBeNull();
  });

  it('version: "unverified" states editing is allowed, never blocked', () => {
    renderWithProviders(
      <>{column('version')(baseRow({ version: { state: 'unverified', observed: '—', declared: '1.0' } }))}</>,
    );
    expect(screen.getByText('Unverified')).not.toBeNull();
  });

  it.each(
    Object.values(configStateVocab).filter((entry) => entry.key !== 'na'),
  )('config: renders the "$label" state distinctly', (entry) => {
    renderWithProviders(<>{column('config')(baseRow({ configState: entry.key }))}</>);
    expect(screen.getAllByText(entry.label).length).toBeGreaterThan(0);
  });

  it('config: "na" renders a dash, not the "N/A" vocab label — an observe-only target', () => {
    renderWithProviders(<>{column('config')(baseRow({ configState: 'na' }))}</>);
    expect(screen.getByText('—')).not.toBeNull();
    expect(screen.queryByText('N/A')).toBeNull();
  });

  it('config: "in_sync" always carries the effective-unverified honesty qualifier (SM-D22)', () => {
    renderWithProviders(<>{column('config')(baseRow({ configState: 'in_sync' }))}</>);
    expect(screen.getByText('Applied')).not.toBeNull();
    expect(screen.getByText('effective-unverified')).not.toBeNull();
  });

  it.each(Object.values(driverVocab))('driver: renders "$label" via a pure vocab lookup', (entry) => {
    renderWithProviders(<>{column('driver')(baseRow({ driver: entry.key }))}</>);
    expect(screen.getByText(entry.label)).not.toBeNull();
  });

  it.each(Object.values(locusVocab))('locus: renders "$label" via a pure vocab lookup', (entry) => {
    renderWithProviders(<>{column('locus')(baseRow({ locus: entry.key }))}</>);
    expect(screen.getByText(entry.label)).not.toBeNull();
  });

  it('last seen: "never" renders a dash with the bootstrap-case tooltip text, not a fabricated timestamp', () => {
    renderWithProviders(<>{column('lastSeen')(baseRow({ lastSeen: 'never' }))}</>);
    expect(screen.getByText('—')).not.toBeNull();
  });
  it('renders the package version as a neutral, monospace chip', () => {
    renderWithProviders(<>{column('packageVersion')(baseRow({ packageVersion: 6 }))}</>);

    const chip = screen.getByText('6');
    expect(chip).not.toBeNull();
    // NOT a semantic tone — `ok`/`warn`/`err` mean health here, and a
    // version is not a health state.
    expect(getComputedStyle(chip).fontFamily).toBe('monospace');
  });

  it('sorts and exports the package version as a string, whatever its type', () => {
    const def = columns.find((c) => c.field === 'packageVersion');

    expect(def?.valueGetter?.(baseRow({ packageVersion: 6 }))).toBe('6');
    expect(def?.valueGetter?.(baseRow({ packageVersion: 'dev-2026.08.17' }))).toBe(
      'dev-2026.08.17',
    );
  });

  it('places the package version between Env and Where it runs', () => {
    expect(columns.map((c) => c.field)).toEqual([
      'name',
      'env',
      'packageVersion',
      'driver',
      'locus',
      'health',
      'version',
      'config',
      'replicas',
      'lastSeen',
    ]);
  });

});
