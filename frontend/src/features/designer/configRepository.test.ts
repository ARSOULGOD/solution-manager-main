import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createConfigRepository, mapWireDesignerToLocal } from './configRepository';
import type { GetSolutionPackage } from './configRepository';

const dpClient = createDpClient('/api');

/** A minimal stand-in for `solutionsRepository.getPackage` — the real injected dependency
 * (`configRepository.ts`'s own `GetSolutionPackage`). Returns a fixed, tiny package regardless of
 * what is asked for; these tests only exercise `loadDesigner`'s WIRING (does it call this with the
 * wire-reported solution/version, does it fold the result into `DesignerLoad.package`), not real
 * package content — that is `handlers/cp.ts`'s own `/cp/solutions/:id/package` default route,
 * which the fixture-backed tests below use instead. */
function stubGetSolutionPackage(calls: { solutionId: string; packageVersion: unknown }[]): GetSolutionPackage {
  return (solutionId, packageVersion) => {
    calls.push({ solutionId, packageVersion });
    return Promise.resolve({
      solution: solutionId,
      packageVersion: [1],
      appliesTo: '*',
      provenance: { author: 'test' },
      authoring: { reviewedRows: 0, totalRows: 0 },
      components: [],
    });
  };
}

describe('createConfigRepository (plan 008 Tasks 1/2 — the real getDesigner/stageConfig contract)', () => {
  it('loads the live Connections catalog for package-declared valueFrom bindings', async () => {
    const repo = createConfigRepository(
      dpClient,
      stubGetSolutionPackage([]),
      undefined,
      () => Promise.resolve([
        {
          connectionId: 'solace-prod-01',
          name: 'Production broker',
          connectionType: 'solace-broker',
          attributes: { host: 'tcps://broker.example', port: 55443, vpn: 'prod' },
          active: true,
          updatedAt: '2026-10-11T10:00:00Z',
        },
      ]),
    );

    const load = await repo.loadDesigner('cache-prod-01');

    expect(load.connectionCatalog).toEqual([
      {
        id: 'solace-prod-01',
        label: 'solace-prod-01',
        connectionType: 'solace-broker',
        attributes: { host: 'tcps://broker.example', port: 55443, vpn: 'prod' },
        revision: Date.parse('2026-10-11T10:00:00Z'),
      },
    ]);
  });

  it('uses the catalog solution UUID from the route when the wire view model contains only the package name', async () => {
    const calls: { solutionId: string; packageVersion: unknown }[] = [];
    const repo = createConfigRepository(
      dpClient,
      stubGetSolutionPackage(calls),
      '40e061ec-3bf4-4093-9345-5f05c3f1d352',
    );

    await repo.loadDesigner('cache-prod-01');

    expect(calls).toEqual([
      { solutionId: '40e061ec-3bf4-4093-9345-5f05c3f1d352', packageVersion: 7 },
    ]);
  });

  it('loads a designer view model through the real getDesigner wire shape, fetches the raw package via the injected dependency, saves, and reads history', async () => {
    const calls: { solutionId: string; packageVersion: unknown }[] = [];
    const repo = createConfigRepository(dpClient, stubGetSolutionPackage(calls));

    const load = await repo.loadDesigner('cache-prod-01');
    // `handlers/dp.ts`'s default `/designer` route serves `cache`'s real fixture package via
    // `buildWireDesigner` — `viewModel.solution` is what `loadDesigner` must hand the injected
    // `getSolutionPackage`, never a guess.
    expect(calls).toEqual([{ solutionId: 'cache', packageVersion: 7 }]);
    expect(load.package.solution).toBe('cache');
    expect(load.packageVersion).toBe(7);
    expect(typeof load.stateVersion).toBe('number');
    // The real wire `generation` is `number | null` — carried through as a string key
    // (`DesignerLoad.configGeneration`'s own doc), truthy once a generation exists.
    expect(load.configGeneration).toBeTruthy();
    expect(load.unsupportedFields).toContain('observed.restartObservedValues');

    const saved = await repo.save('cache-prod-01', {
      baseStateVersion: load.stateVersion,
      componentSelection: { instances: [{ instanceKey: 'cache-engine', type: 'cache-engine' }] },
      draftValues: {},
      rendered: [],
    });
    expect(typeof saved.generation).toBe('number');
    expect(typeof saved.stateVersion).toBe('number');
    expect(saved.correlationId).toBeTruthy();

    const history = await repo.history('cache-prod-01');
    expect(history.length).toBeGreaterThan(0);
  });

  it('a stale baseStateVersion is refused 409, not folded into a generic error', async () => {
    const repo = createConfigRepository(dpClient, stubGetSolutionPackage([]));

    await expect(
      repo.save('cache-prod-01', {
        baseStateVersion: -1,
        componentSelection: { instances: [] },
        draftValues: {},
        rendered: [],
      }),
    ).rejects.toMatchObject({ status: 409 });
  });
});

describe('createConfigRepository — getConfigHistory (console.yaml ConfigHistory envelope)', () => {
  it('maps the real envelope (`stateVersion` + `entries[].{generation,at,by}`) — not the old `{generation,savedAt,savedBy}[]` fixture shape', async () => {
    const repo = createConfigRepository(dpClient, stubGetSolutionPackage([]));

    const history = await repo.history('cache-prod-01');

    expect(history.length).toBeGreaterThan(0);
    const [first] = history;
    expect(first).toBeDefined();
    // `generation` is `int64` on the wire (console.yaml ConfigHistory.entries[].generation),
    // never the old fixture's string.
    expect(typeof first?.generation).toBe('number');
    expect(typeof first?.at).toBe('string');
    expect(typeof first?.by).toBe('string');
  });
});

describe('createConfigRepository — setConfigSecret (I6)', () => {
  it('never echoes the value back — the 200 is a StagedConfig, not a {ref} (console.yaml setConfigSecret)', async () => {
    const repo = createConfigRepository(dpClient, stubGetSolutionPackage([]));

    const result = await repo.setSecret('cache-prod-01', 'solace.password', {
      value: 'super-secret-value',
      baseStateVersion: 41,
    });

    // I6: the literal must never round-trip in the response, in any field,
    // under any key — this assertion fails the moment a future change adds
    // an echo field, a debug field, or re-introduces the old `{ref}` shape
    // backed by a value instead of a server-minted reference.
    expect(JSON.stringify(result)).not.toContain('super-secret-value');
    // console.yaml: the 200 is a `StagedConfig` — `stateVersion` + `generation` only.
    expect(typeof result.stateVersion).toBe('number');
    expect(typeof result.generation).toBe('number');
    expect(result).not.toHaveProperty('ref');
    expect(result).not.toHaveProperty('value');
  });
});

describe('mapWireDesignerToLocal (plan 008 Task 1 — the reverse mapping, unit-tested on its own)', () => {
  function wireFixture(overrides: Partial<Parameters<typeof mapWireDesignerToLocal>[0]> = {}) {
    return {
      stateVersion: 1,
      generation: 41,
      layout: {},
      viewModel: {
        engineVersion: 1,
        solution: 'cache',
        packageVersion: 6,
        components: [
          {
            instanceKey: 'cache-engine',
            type: 'cache-engine',
            role: 'none' as const,
            sections: [
              {
                title: 'General',
                order: 0,
                fields: [
                  {
                    key: 'namespace',
                    renderedPath: 'cache.namespace',
                    controlKind: 'text',
                    value: { kind: 'value' as const, value: 'ledger-prod', source: 'draft' as const },
                    visible: true,
                    enabled: true,
                    virtual: false,
                    advanced: false,
                    masked: false,
                    discriminator: false,
                    validations: [],
                    hiddenBy: [],
                  },
                  {
                    key: 'computed-default',
                    renderedPath: 'cache.computed-default',
                    controlKind: 'text',
                    value: { kind: 'value' as const, value: 'from-default', source: 'default' as const },
                    visible: true,
                    enabled: true,
                    virtual: false,
                    advanced: false,
                    masked: false,
                    discriminator: false,
                    validations: [],
                    hiddenBy: [],
                  },
                  {
                    key: 'solace.password',
                    renderedPath: 'cache.solace.password',
                    controlKind: 'secret',
                    value: { kind: 'secretUnset' as const },
                    visible: true,
                    enabled: true,
                    virtual: false,
                    advanced: false,
                    masked: true,
                    discriminator: false,
                    validations: [],
                    hiddenBy: [],
                  },
                ],
                actions: [],
                allVisibleFieldsAdvanced: false,
              },
            ],
          },
        ],
        rendered: [],
        excluded: [],
        metrics: [],
        status: {
          version: { state: 'OK' as const, declared: '7.2', observed: '7.2', editingBlocked: false },
          config: { persisted: 'x', restartObserved: 'x' },
          replicas: [],
          healthRollup: 'GREEN' as const,
          healthAvailability: 'declared' as const,
        },
        diagnostics: [],
      },
      ...overrides,
    };
  }

  it('maps a draft-sourced field into DraftValues, and leaves default/secret fields out of it', () => {
    const mapped = mapWireDesignerToLocal(wireFixture());

    expect(mapped.draftValues['cache-engine']).toEqual({ namespace: 'ledger-prod' });
  });

  it('I6: no secret literal ever reaches DraftValues, under any key', () => {
    const mapped = mapWireDesignerToLocal(wireFixture());

    expect(JSON.stringify(mapped.draftValues)).not.toMatch(/password/i);
  });

  it('maps StatusView into this engine\'s flat ObservedState', () => {
    const mapped = mapWireDesignerToLocal(wireFixture());

    expect(mapped.observedState).toMatchObject({
      health: 'healthy',
      versionState: 'ok',
      declaredVersion: '7.2',
      observedVersion: '7.2',
    });
  });

  it('flags the two fields it cannot derive, rather than silently fabricating them', () => {
    const mapped = mapWireDesignerToLocal(wireFixture());

    expect(mapped.unsupportedFields).toHaveLength(2);
    expect(mapped.unsupportedFields.join(' ')).toMatch(/restartObservedValues/);
    expect(mapped.unsupportedFields.join(' ')).toMatch(/configState/);
  });

  it('derives componentTypes from exactly the wire components present', () => {
    const mapped = mapWireDesignerToLocal(wireFixture());

    expect(mapped.componentTypes).toEqual(['cache-engine']);
  });

  it('approximates configState as "na" when nothing is persisted yet', () => {
    const wire = wireFixture();
    wire.viewModel.status.config = {};

    expect(mapWireDesignerToLocal(wire).observedState.configState).toBe('na');
  });

  it('approximates configState as "diverged" when the restart-observed tier disagrees with persisted', () => {
    const wire = wireFixture();
    wire.viewModel.status.config = { persisted: 'a', restartObserved: 'b' };

    expect(mapWireDesignerToLocal(wire).observedState.configState).toBe('diverged');
  });

  it('malformed input (missing components array) throws rather than silently producing an empty draft', () => {
    const wire = wireFixture();
    (wire.viewModel as { components?: unknown }).components = undefined;

    expect(() => mapWireDesignerToLocal(wire)).toThrow();
  });
});
