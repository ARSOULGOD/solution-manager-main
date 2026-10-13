/**
 * Rewritten for integration plan 007 (2026-10-09): the real
 * `declareInstance` body carries no `componentTypes`/`connections`/
 * `replicas` to round-trip — see `declaredInstance.ts`'s DEGRADED note.
 * This suite now covers `readDeclareInput`'s real-shape validation and
 * `buildDeclaredInstance`'s placeholder-filled row.
 */
import { describe, expect, it } from 'vitest';

import { buildDeclaredInstance, readDeclareInput } from './declaredInstance';

const VALID = {
  solutionId: 'cache',
  name: 'cache-prod-01',
  targetId: 'target-1',
  declaredPackageVersion: 7,
  placement: {
    containerName: 'cache-prod-01',
    configDirectory: '/etc/cache',
    containerConfigPath: '/config',
    fileName: 'application.yml',
    healthPort: 8080,
  },
  acquisition: { mode: 'direct-http' },
};

describe('readDeclareInput', () => {
  it('accepts a well-formed real-shaped body', () => {
    const parsed = readDeclareInput(VALID);
    expect(parsed).toEqual(VALID);
  });

  it('rejects a missing name', () => {
    expect(readDeclareInput({ ...VALID, name: '' })).toBeUndefined();
  });

  it('rejects a name that is not a valid instance name', () => {
    expect(readDeclareInput({ ...VALID, name: 'Not Valid' })).toBeUndefined();
  });

  it('rejects a missing targetId', () => {
    const withoutTarget: Record<string, unknown> = { ...VALID };
    delete withoutTarget['targetId'];
    expect(readDeclareInput(withoutTarget)).toBeUndefined();
  });

  it('accepts a string-valued declaredPackageVersion', () => {
    const parsed = readDeclareInput({ ...VALID, declaredPackageVersion: 'dev-2026.08.17' });
    expect(parsed?.declaredPackageVersion).toBe('dev-2026.08.17');
  });

  it('rejects a placement missing a required field', () => {
    const placementWithoutPort: Record<string, unknown> = { ...VALID.placement };
    delete placementWithoutPort['healthPort'];
    expect(readDeclareInput({ ...VALID, placement: placementWithoutPort })).toBeUndefined();
  });

  it('rejects a non-numeric healthPort', () => {
    expect(
      readDeclareInput({ ...VALID, placement: { ...VALID.placement, healthPort: 'not-a-port' } }),
    ).toBeUndefined();
  });

  it('rejects an acquisition mode outside the closed vocabulary', () => {
    expect(readDeclareInput({ ...VALID, acquisition: { mode: 'carrier-pigeon' } })).toBeUndefined();
  });

  it('trims string fields', () => {
    const parsed = readDeclareInput({ ...VALID, name: ' cache-prod-01 ' });
    expect(parsed?.name).toBe('cache-prod-01');
  });
});

describe('buildDeclaredInstance', () => {
  it('builds a row from the name and placement container name', () => {
    const parsed = readDeclareInput(VALID);
    expect(parsed).toBeDefined();
    const row = buildDeclaredInstance(parsed!);
    expect(row.id).toBe('cache-prod-01');
    expect(row.name).toBe('cache-prod-01');
    expect(row.solutionId).toBe('cache');
    expect(row.packageVersion).toBe(7);
    expect(row.replicas).toEqual([{ name: 'cache-prod-01', health: 'not_applicable' }]);
  });

  it('every observed fact reads not-yet-observed, never a plausible-looking default', () => {
    const parsed = readDeclareInput(VALID);
    const row = buildDeclaredInstance(parsed!);
    expect(row.healthRollup).toBe('not_applicable');
    expect(row.configState).toBe('na');
    expect(row.lastSeen).toBe('never');
    expect(row.version).toEqual({ state: 'unverified', observed: '—', declared: '—' });
  });
});
