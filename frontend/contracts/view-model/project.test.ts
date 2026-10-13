/**
 * `valueFrom` resolution (`UI-D28`) — this engine's port of the canonical
 * suite in `contracts/view-models/src/project.test.ts`. Same scenarios, same
 * intent; assertions are adapted to THIS file's own `types.ts` shapes where
 * the two engines' contracts genuinely differ (see the task 5 report for the
 * full list — e.g. this `ViewModel` keeps a flat `sections[]` rather than
 * `components[].sections[]`, `ComponentSelection` is a plain array keyed by
 * `instanceId` rather than `{ instances: [...] }` keyed by `instanceKey`,
 * and `Validation` carries `tier: 'bind' | 'semantic'` rather than
 * `severity: 'block' | 'warn'`).
 */
import { describe, expect, it } from 'vitest';

import FILE_MESH_PACKAGE from '@contracts/packages/file-mesh/package.json';
import { toFrontendPackage } from '@contracts/solution-package';
import type { CanonicalSolutionPackage } from '@contracts/solution-package/types';

import { project } from './project';
import type {
  Catalog,
  ComponentSelection,
  ObservedState,
  SolutionPackage,
  ViewField,
  ViewModel,
} from './types';

const BROKER_PKG: SolutionPackage = {
  solution: 'cache',
  packageVersion: [7],
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
        {
          key: 'solace.username',
          type: 'string',
          default: 'guest',
          valueFrom: { ref: 'solaceBroker', attribute: 'username' },
          ui: { section: 'Broker' },
        },
      ],
    },
  ],
};

const CATALOG: Catalog = {
  connection: [
    {
      id: 'conn-7',
      label: 'prod-broker-01',
      connectionType: 'solace-broker',
      revision: 4,
      attributes: { host: 'tcps://broker.acme.internal', port: 55443, vpn: 'payments-prod' },
      // NOTE: `username` deliberately absent — exercises precedence row 3 (falls to default).
    },
  ],
};

// `ComponentSelection` here is a flat array of `{ type, instanceId }`, not `{ instances: [...] }`
// keyed by `instanceKey` — this file's own shape (see `types.ts`'s `ComponentSelectionEntry`).
const SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'i1' }];

const OBSERVED_OK: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

// This engine's `ViewModel` already keeps a flat `sections[]` (not nested under
// `components[]`), so — unlike the canonical brief's buggy `vm.sections` snippet — this really
// is the correct lookup for this file's own shape.
function fieldOf(vm: ViewModel, key: string): ViewField | undefined {
  return vm.sections.flatMap((s) => s.fields).find((f) => f.key === key);
}

describe('valueFrom resolution (UI-D28)', () => {
  it('resolves a bound field from the selected connection', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, 7, CATALOG);
    const host = fieldOf(vm, 'solace.host');
    expect(host?.value).toEqual({ kind: 'value', value: 'tcps://broker.acme.internal', source: 'connection' });
    expect(host?.enabled).toBe(false); // bound fields are read-only until overridden
  });

  it('falls through to the declared default when the connection leaves an attribute unset', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, 7, CATALOG);
    expect(fieldOf(vm, 'solace.username')?.value).toEqual({ kind: 'value', value: 'guest', source: 'default' });
  });

  it('treats a literal draft value on a bound field as an override', () => {
    const vm = project(
      BROKER_PKG,
      SELECTION,
      { i1: { solaceBroker: 'conn-7', 'solace.port': 55556 } },
      OBSERVED_OK,
      7,
      CATALOG,
    );
    const port = fieldOf(vm, 'solace.port');
    expect(port?.value).toEqual({ kind: 'value', value: 55556, source: 'override' });
    expect(port?.enabled).toBe(true); // an override is editable
  });

  it('blocks a required bound field when no connection is selected', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, 7, CATALOG);
    const host = fieldOf(vm, 'solace.host');
    expect(host?.value).toEqual({ kind: 'unset' });
    // This file's `Validation` carries `tier: 'bind' | 'semantic'`, not the canonical
    // engine's `severity: 'block' | 'warn'` — `REQUIRED` is this engine's `bind` tier.
    expect(host?.validations.some((v) => v.tier === 'bind' && v.code === 'REQUIRED')).toBe(true);
  });

  it('diagnoses a selector pointing at a connection the catalog does not hold', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: { solaceBroker: 'conn-99' } }, OBSERVED_OK, 7, CATALOG);
    expect(vm.diagnostics?.some((d) => d.code === 'CONNECTION_UNRESOLVED')).toBe(true);
  });

  it('never lets a resolved secret reach a FieldValue (I6)', () => {
    const pkg: SolutionPackage = {
      ...BROKER_PKG,
      components: [
        {
          ...BROKER_PKG.components[0]!,
          fields: [
            BROKER_PKG.components[0]!.fields[0]!,
            {
              key: 'solace.password',
              type: 'secret',
              secret: true,
              valueFrom: { ref: 'solaceBroker', attribute: 'password' },
              ui: { section: 'Broker' },
            },
          ],
        },
      ],
    };
    const catalog: Catalog = {
      connection: [{ ...CATALOG.connection![0]!, attributes: { ...CATALOG.connection![0]!.attributes, password: 'hunter2' } }],
    };
    const vm = project(pkg, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, 7, catalog);
    expect(fieldOf(vm, 'solace.password')?.value).toEqual({ kind: 'secretUnset' });
  });

  it('refuses to resolve a secret attribute into a non-secret field (I6)', () => {
    const pkg: SolutionPackage = {
      ...BROKER_PKG,
      components: [
        {
          ...BROKER_PKG.components[0]!,
          fields: [
            BROKER_PKG.components[0]!.fields[0]!,
            {
              key: 'solace.passwordPlain',
              type: 'string',
              valueFrom: { ref: 'solaceBroker', attribute: 'password' },
              ui: { section: 'Broker' },
            },
          ],
        },
      ],
    };
    const catalog: Catalog = {
      connection: [
        {
          ...CATALOG.connection![0]!,
          attributes: { ...CATALOG.connection![0]!.attributes, password: 'hunter2' },
          secretAttributes: ['password'],
        },
      ],
    };
    const vm = project(pkg, SELECTION, { i1: { solaceBroker: 'conn-7' } }, OBSERVED_OK, 7, catalog);

    expect(fieldOf(vm, 'solace.passwordPlain')?.value).toEqual({ kind: 'unset' });
    expect(vm.diagnostics?.some((d) => d.code === 'SECRET_BINDING_REFUSED')).toBe(true);
    expect(JSON.stringify(vm)).not.toContain('hunter2');
  });

  it("resolves a connection selector's options from the catalog, by id", () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, 7, CATALOG);
    const selector = fieldOf(vm, 'solaceBroker');
    // `CATALOG` (module-level) holds exactly one entry, `conn-7` (label 'prod-broker-01',
    // connectionType 'solace-broker') — options must be the id, never the label.
    expect(selector?.options).toEqual(['conn-7']);
  });

  it('offers only entries whose connectionType matches the selector', () => {
    const catalog: Catalog = {
      connection: [
        { id: 'conn-a', label: 'Broker A', connectionType: 'solace-broker', revision: 1, attributes: {} },
        { id: 'conn-b', label: 'Broker B', connectionType: 'solace-broker', revision: 1, attributes: {} },
        { id: 'conn-c', label: 'Remote C', connectionType: 'remote-server', revision: 1, attributes: {} },
      ],
    };
    const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, 7, catalog);
    // `solaceBroker` declares `optionsSource.connectionType: 'solace-broker'` — the
    // remote-server entry must never appear.
    expect(fieldOf(vm, 'solaceBroker')?.options).toEqual(['conn-a', 'conn-b']);
  });

  it('diagnoses a selector whose catalog was not supplied', () => {
    const vm = project(BROKER_PKG, SELECTION, { i1: {} }, OBSERVED_OK, 7, {});
    expect(fieldOf(vm, 'solaceBroker')?.options).toEqual([]);
    expect(vm.diagnostics?.some((d) => d.code === 'CATALOG_UNRESOLVED' && d.key === 'solaceBroker')).toBe(true);
  });

  it('resolves every non-secret File Mesh Solace field from the selected package connection', () => {
    const pkg = toFrontendPackage(FILE_MESH_PACKAGE as unknown as CanonicalSolutionPackage);
    const source = pkg.components.find((component) => component.type === 'file-source');
    expect(source).toBeDefined();

    const vm = project(
      pkg,
      [
        { type: 'file-source', instanceId: 'source-1' },
        { type: 'solace', instanceId: 'solace-1' },
      ],
      {
        'solace-1': {
          solaceManagementBroker: 'mgmt-01',
          solaceDataBroker: 'data-01',
        },
      },
      OBSERVED_OK,
      1,
      {
        connection: [
          {
            id: 'mgmt-01',
            label: 'mgmt-01',
            connectionType: 'solace-broker',
            revision: 1,
            attributes: { host: 'tcps://management.example', vpn: 'management', username: 'mgmt-user' },
          },
          {
            id: 'data-01',
            label: 'data-01',
            connectionType: 'solace-broker',
            revision: 1,
            attributes: { host: 'tcps://data.example', vpn: 'data', username: 'data-user' },
          },
        ],
      },
      'source',
    );

    const field = (key: string) => fieldOf(vm, key);
    expect(field('solace.connector.management.session.host')?.value).toEqual({
      kind: 'value',
      value: 'tcps://management.example',
      source: 'connection',
    });
    expect(field('solace.connector.management.session.msg-vpn')?.value).toEqual({
      kind: 'value',
      value: 'management',
      source: 'connection',
    });
    expect(field('solace.connector.management.session.client-username')?.value).toEqual({
      kind: 'value',
      value: 'mgmt-user',
      source: 'connection',
    });
    expect(field('solace.java.host')?.value).toEqual({
      kind: 'value',
      value: 'tcps://data.example',
      source: 'connection',
    });
    expect(field('solace.java.msg-vpn')?.value).toEqual({
      kind: 'value',
      value: 'data',
      source: 'connection',
    });
    expect(field('solace.java.client-username')?.value).toEqual({
      kind: 'value',
      value: 'data-user',
      source: 'connection',
    });
    expect(field('solace.connector.management.session.client-password')?.value).toEqual({ kind: 'secretUnset' });
    expect(field('solace.java.client-password')?.value).toEqual({ kind: 'secretUnset' });
    expect(field('solaceManagementBroker')?.options).toEqual(['mgmt-01', 'data-01']);
    expect(field('solaceDataBroker')?.options).toEqual(['mgmt-01', 'data-01']);
  });
});

describe('package-defined field roles', () => {
  it('keeps matching and shared fields and excludes fields for another role', () => {
    const pkg: SolutionPackage = {
      ...BROKER_PKG,
      components: [{
        ...BROKER_PKG.components[0]!,
        fields: [
          { key: 'sourceOnly', type: 'string', role: 'source' },
          { key: 'sinkOnly', type: 'string', role: 'sink' },
          { key: 'sharedLegacy', type: 'string' },
          { key: 'sharedNone', type: 'string', role: 'none' },
        ],
      }],
    };
    const fields = (role: string) =>
      project(pkg, SELECTION, { i1: {} }, OBSERVED_OK, 7, {}, role).sections
        .flatMap((section) => section.fields).map((field) => field.key);

    expect(fields('source')).toEqual(['sourceOnly', 'sharedLegacy', 'sharedNone']);
    expect(fields('sink')).toEqual(['sinkOnly', 'sharedLegacy', 'sharedNone']);
    expect(project(pkg, SELECTION, { i1: {} }, OBSERVED_OK, 7).sections
      .flatMap((section) => section.fields).map((field) => field.key))
      .toEqual(['sourceOnly', 'sinkOnly', 'sharedLegacy', 'sharedNone']);
  });
});
