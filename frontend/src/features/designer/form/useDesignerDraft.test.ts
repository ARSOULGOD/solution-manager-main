import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import CACHE_PACKAGE from '@contracts/packages/cache/package.json';
import type {
  Catalog,
  ComponentSelection,
  DraftValues,
  ObservedState,
  SolutionPackage,
  ViewField,
  ViewModel,
} from '@contracts/view-model/types';

import { useDesignerDraft } from './useDesignerDraft';
import type { UseDesignerDraftArgs } from './useDesignerDraft';

function fieldOf(vm: ViewModel, key: string): ViewField | undefined {
  return vm.sections.flatMap((section) => section.fields).find((field) => field.key === key);
}

/**
 * `UI-D28` — a minimal Cache-shaped broker binding: `solaceBroker` selects a connection,
 * `solace.host`/`solace.port` bind to its `host`/`port` attributes. Mirrors the canonical
 * `BROKER_PKG`/`CATALOG` fixture in `contracts/view-model/project.test.ts` (`conn-7`'s `port`
 * is `55443`, same value this file's own `src/shared/testdoubles/data/connections.ts` uses for
 * both `conn-7` and `conn-8` — not a coincidence to be confused for a bug).
 */
const BROKER_PKG: SolutionPackage = {
  solution: 'cache',
  packageVersion: [1],
  appliesTo: 'cache-engine',
  provenance: { author: 'test' },
  authoring: { reviewedRows: 1, totalRows: 1 },
  components: [
    {
      type: 'cache-engine',
      cardinality: 'exactly-one',
      configPath: 'application.yml',
      loggerPrefix: 'cache',
      fields: [
        {
          key: 'solaceBroker',
          type: 'enum',
          virtual: true,
          required: true,
          optionsSource: { kind: 'sm-catalog', entity: 'connection', connectionType: 'solace-broker' },
          ui: { section: 'Broker' },
        },
        {
          key: 'solace.host',
          type: 'string',
          required: true,
          valueFrom: { ref: 'solaceBroker', attribute: 'host' },
          ui: { section: 'Broker' },
        },
        {
          key: 'solace.port',
          type: 'int',
          default: 55555,
          valueFrom: { ref: 'solaceBroker', attribute: 'port' },
          ui: { section: 'Broker' },
        },
      ],
    },
  ],
};

const BROKER_CATALOG: Catalog = {
  connection: [
    {
      id: 'conn-7',
      label: 'prod-broker-01',
      connectionType: 'solace-broker',
      revision: 4,
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod' },
    },
    {
      id: 'conn-8',
      label: 'stage-broker-01',
      connectionType: 'solace-broker',
      revision: 1,
      attributes: { host: 'tcps://broker-st.acme.internal', port: 55443, vpn: 'payments-stage' },
    },
  ],
};

const BROKER_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'i1' }];
const BROKER_OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

const BROKER_ARGS: UseDesignerDraftArgs = {
  pkg: BROKER_PKG,
  componentSelection: BROKER_SELECTION,
  observedState: BROKER_OBSERVED,
  initialDraft: { i1: { solaceBroker: 'conn-7' } },
  packageVersion: 1,
  catalog: BROKER_CATALOG,
};

describe('useDesignerDraft — connection provenance and override (UI-D28)', () => {
  it('override copies the resolved value into the draft as a literal', () => {
    const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));

    act(() => {
      result.current.onOverride('i1', 'solace.port');
    });

    expect(result.current.getInstanceValues('i1')['solace.port']).toBe(55443);
    const port = fieldOf(result.current.viewModel, 'solace.port');
    expect(port?.value).toMatchObject({ source: 'override' });
  });

  it('revert removes the literal so the field binds again', () => {
    const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));

    act(() => {
      result.current.onOverride('i1', 'solace.port');
    });
    act(() => {
      result.current.onFieldChange('i1', 'solace.port', 55556);
    });
    act(() => {
      result.current.onRevert('i1', 'solace.port');
    });

    expect(fieldOf(result.current.viewModel, 'solace.port')?.value).toMatchObject({
      value: 55443,
      source: 'connection',
    });
  });

  it('changing the selector re-binds non-overridden fields and keeps overrides', () => {
    const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));

    act(() => {
      result.current.onOverride('i1', 'solace.port');
    });
    act(() => {
      result.current.onFieldChange('i1', 'solace.port', 55556);
    });
    act(() => {
      result.current.onFieldChange('i1', 'solaceBroker', 'conn-8');
    });

    expect(fieldOf(result.current.viewModel, 'solace.host')?.value).toMatchObject({
      value: 'tcps://broker-st.acme.internal',
      source: 'connection',
    });
    expect(fieldOf(result.current.viewModel, 'solace.port')?.value).toMatchObject({
      value: 55556,
      source: 'override',
    });
  });

  it('a bound field contributes its reference, not its value, to the save payload', () => {
    const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));

    expect(result.current.payload.i1!['solace.host']).toEqual({ $from: 'conn-7#host' });
    expect(result.current.payload.i1!['solace.port']).toEqual({ $from: 'conn-7#port' });
  });

  it('an overridden field contributes its literal, not a reference, to the save payload', () => {
    const { result } = renderHook(() => useDesignerDraft(BROKER_ARGS));

    act(() => {
      result.current.onOverride('i1', 'solace.port');
    });
    act(() => {
      result.current.onFieldChange('i1', 'solace.port', 55556);
    });

    expect(result.current.payload.i1!['solace.port']).toBe(55556);
  });

  it('onOverride is a no-op when the field has no resolved value', () => {
    const { result } = renderHook(() =>
      useDesignerDraft({ ...BROKER_ARGS, initialDraft: { i1: {} } }),
    );

    act(() => {
      result.current.onOverride('i1', 'nonexistent-field');
    });

    expect(result.current.getInstanceValues('i1')).not.toHaveProperty('nonexistent-field');
  });
});

function pkg(): SolutionPackage {
  return {
    solution: 'file-mesh',
    packageVersion: [1],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'solace', signature: 'test-fixture' },
    authoring: { reviewedRows: 2, totalRows: 2 },
    components: [
      {
        type: 'source-grid',
        cardinality: 'one-of-many',
        configPath: 'source-grid',
        loggerPrefix: 'com.solace.solutions.filemesh.source',
        fields: [
          { key: 'ACTION', type: 'enum', options: ['download', 'deploy'], discriminator: true },
          {
            key: 'DEPLOYMENT_MODE',
            type: 'enum',
            options: ['standalone', 'highavailable'],
            when: { ACTION: 'deploy' },
            default: 'standalone',
          },
        ],
      },
    ],
  };
}

const componentSelection: ComponentSelection = [{ type: 'source-grid', instanceId: 'source-1' }];
const observedState: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

describe('useDesignerDraft', () => {
  it('retains a hidden branch value across a discriminator switch away and back', () => {
    const initialDraft: DraftValues = {
      'source-1': { ACTION: 'download', DEPLOYMENT_MODE: 'highavailable' },
    };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    act(() => result.current.onFieldChange('source-1', 'ACTION', 'deploy'));
    expect(result.current.getInstanceValues('source-1')['DEPLOYMENT_MODE']).toBe('highavailable');

    act(() => result.current.onFieldChange('source-1', 'ACTION', 'download'));
    expect(result.current.getInstanceValues('source-1')).toEqual({
      ACTION: 'download',
      DEPLOYMENT_MODE: 'highavailable',
    });
  });

  it('seeds a newly-active branch field with its declared default, not left unset', () => {
    const initialDraft: DraftValues = { 'source-1': { ACTION: 'download' } };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    act(() => result.current.onFieldChange('source-1', 'ACTION', 'deploy'));

    expect(result.current.getInstanceValues('source-1')).toEqual({
      ACTION: 'deploy',
      DEPLOYMENT_MODE: 'standalone',
    });
  });

  it('does not re-run the reset-by-merge for a non-discriminator field edit', () => {
    const initialDraft: DraftValues = { 'source-1': { ACTION: 'deploy', DEPLOYMENT_MODE: 'highavailable' } };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    act(() => result.current.onFieldChange('source-1', 'DEPLOYMENT_MODE', 'standalone'));

    expect(result.current.getInstanceValues('source-1')).toEqual({
      ACTION: 'deploy',
      DEPLOYMENT_MODE: 'standalone',
    });
  });

  it('excludes an inactive branch field from the save payload', () => {
    const initialDraft: DraftValues = {
      'source-1': { ACTION: 'download', DEPLOYMENT_MODE: 'highavailable' },
    };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    expect(result.current.payload).toEqual({ 'source-1': { ACTION: 'download' } });
  });

  it('includes a visible field in the save payload once its branch is active', () => {
    const initialDraft: DraftValues = { 'source-1': { ACTION: 'deploy', DEPLOYMENT_MODE: 'highavailable' } };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    expect(result.current.payload).toEqual({
      'source-1': { ACTION: 'deploy', DEPLOYMENT_MODE: 'highavailable' },
    });
  });

  it('tracks isDirty by deep comparison against the baseline, surviving a reset-by-merge', () => {
    const initialDraft: DraftValues = { 'source-1': { ACTION: 'download' } };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkg(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    expect(result.current.isDirty).toBe(false);
    expect(result.current.dirtyInstanceCount).toBe(0);

    act(() => result.current.onFieldChange('source-1', 'ACTION', 'deploy'));

    expect(result.current.isDirty).toBe(true);
    expect(result.current.dirtyInstanceCount).toBe(1);

    act(() => result.current.onFieldChange('source-1', 'ACTION', 'download'));

    expect(result.current.isDirty).toBe(false);
    expect(result.current.dirtyInstanceCount).toBe(0);
  });
});

describe('useDesignerDraft — secret fields', () => {
  function pkgWithSecret(): SolutionPackage {
    const base = pkg();
    return {
      ...base,
      components: [
        {
          ...base.components[0]!,
          fields: [...base.components[0]!.fields, { key: 'TOKEN', type: 'secret', secret: true }],
        },
      ],
    };
  }

  it('never resolves a secret field into the save payload (I6)', () => {
    const initialDraft: DraftValues = { 'source-1': { ACTION: 'download', TOKEN: 'super-secret' } };
    const { result } = renderHook(() =>
      useDesignerDraft({ pkg: pkgWithSecret(), componentSelection, observedState, initialDraft, packageVersion: 1 }),
    );

    expect(result.current.payload).toEqual({ 'source-1': { ACTION: 'download' } });
  });
});

describe('useDesignerDraft — the instance version reaches render and payload alike (FE-121, FE-D37)', () => {
  const CACHE_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'cache-0' }];
  const CACHE_OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

  function cacheKeys(pkg: SolutionPackage, packageVersion: number, initialDraft: DraftValues) {
    const { result } = renderHook(() =>
      useDesignerDraft({
        pkg,
        componentSelection: CACHE_SELECTION,
        observedState: CACHE_OBSERVED,
        initialDraft,
        packageVersion,
      }),
    );
    const renderedKeys = result.current.viewModel.sections.flatMap((section) =>
      section.fields.map((field) => field.key),
    );
    return { renderedKeys, payload: result.current.payload };
  }

  it('renders no v7-only field at v6, and pays out none of it either — the defaults-applied value never surfaces', () => {
    const { renderedKeys, payload } = cacheKeys(CACHE_PACKAGE as SolutionPackage, 6, { 'cache-0': {} });

    expect(renderedKeys).not.toContain('max-weight');
    expect(payload['cache-0']).not.toHaveProperty('max-weight');
    expect(renderedKeys).toContain('max-size-bytes');
    expect(payload['cache-0']).toHaveProperty('max-size-bytes');
  });

  it('renders the v7-only field (with its default) at v7, and pays it out — never the v6-only field', () => {
    const { renderedKeys, payload } = cacheKeys(CACHE_PACKAGE as SolutionPackage, 7, { 'cache-0': {} });

    expect(renderedKeys).toContain('max-weight');
    expect(payload['cache-0']).toHaveProperty('max-weight', 536870912);
    expect(renderedKeys).not.toContain('max-size-bytes');
    expect(payload['cache-0']).not.toHaveProperty('max-size-bytes');
  });

  it('a version-free field renders and pays out identically at v6 and v7', () => {
    const atV6 = cacheKeys(CACHE_PACKAGE as SolutionPackage, 6, { 'cache-0': {} });
    const atV7 = cacheKeys(CACHE_PACKAGE as SolutionPackage, 7, { 'cache-0': {} });

    expect(atV6.renderedKeys).toContain('namespace');
    expect(atV7.renderedKeys).toContain('namespace');
  });
});

describe('useDesignerDraft — a field key containing a dot (task 125)', () => {
  const CACHE_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'cache-0' }];
  const CACHE_OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

  /**
   * `react-hook-form`'s `setValue(name, value)` parses a dot in `name` as
   * a nested-path separator — but a package's own field `key` may
   * legitimately contain one (`solace.host`, `cache-hit-response.*`, real
   * fields in `contracts/packages/cache/package.json`). Calling
   * `setValue('cache-0.solace.host', value)` therefore silently wrote to
   * `values.cache-0.solace.host` (a THREE-level nested path), never to the
   * flat `ComponentValues['solace.host']` key `project()`/`payload` read
   * — an edit to any dotted field was accepted by the UI and then
   * silently lost. Found live in a browser (task 125's Gate D journey),
   * not by an earlier unit test — every prior `useDesignerDraft` fixture
   * used only dot-free keys.
   */
  it('an edit to a dotted field key persists — onFieldChange, getInstanceValues and payload all agree', () => {
    const { result } = renderHook(() =>
      useDesignerDraft({
        pkg: CACHE_PACKAGE as SolutionPackage,
        componentSelection: CACHE_SELECTION,
        observedState: CACHE_OBSERVED,
        initialDraft: { 'cache-0': {} },
        packageVersion: 7,
      }),
    );

    act(() => result.current.onFieldChange('cache-0', 'solace.host', 'broker.example.com'));

    expect(result.current.getInstanceValues('cache-0')['solace.host']).toBe('broker.example.com');
    expect(result.current.payload['cache-0']).toHaveProperty('solace.host', 'broker.example.com');
    // A sibling dotted field, untouched, keeps its own value — proves the
    // fix updates the one field, not the whole nested `solace` branch.
    expect(result.current.payload['cache-0']).toHaveProperty('solace.vpn', 'default');
  });
});
