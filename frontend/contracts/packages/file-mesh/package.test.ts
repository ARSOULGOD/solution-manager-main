/**
 * The File Mesh package, as it is now: a GENERATED, flat package (commit `c97df91`, "File mesh
 * json"; `authoring.reviewedRows` 163/163) that mirrors the connector's own application
 * properties. It REPLACED the hand-authored plan-129 package (`COMP_SOURCE` / `COMP_SINK`, a
 * `connectorType` discriminator, 37 `when` branches, and `*_BINDING` location components), so the
 * tests that described those behaviours were retired rather than ported — the model they pinned
 * (one visible connector subtree chosen by a selector; FE-150's hidden-required rule on it) no
 * longer exists in the package. What it replaced them with is below.
 *
 * Two KNOWN DEFECTS of the generated package are pinned with `it.fails`: each passes while the
 * defect exists and turns RED the moment it is fixed, forcing the marker to be removed — the same
 * two-way ratchet `contractConformance.test.ts` uses. They are reported, not worked around.
 *
 * Sits beside the package it tests; `vitest.config.ts`'s `contracts` project includes
 * `contracts/packages/**` for exactly this reason.
 */
import { describe, expect, it } from 'vitest';

import { toFrontendPackage } from '../../solution-package';
import type { CanonicalSolutionPackage } from '../../solution-package/types';
import { parseAndValidateSolutionPackage } from '../../solution-package/validate';
import { project } from '../../view-model/project';
import type { ObservedState } from '../../view-model/types';

import RAW from './package.json';

const canonical = RAW as unknown as CanonicalSolutionPackage;
const pkg = toFrontendPackage(canonical);
const OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

interface RawField {
  key: string;
  type: string;
  default?: unknown;
  version?: number[];
  virtual?: boolean;
  required?: boolean;
  discriminator?: boolean;
  when?: Record<string, unknown>;
  valueFrom?: { ref: string; attribute: string };
  optionsSource?: { kind: string; entity: string; connectionType?: string };
}
interface RawComponent {
  type: string;
  role: string;
  cardinality: string;
  configPath: string;
  version: number[];
  fields: RawField[];
}
const components = (RAW as unknown as { components: RawComponent[] }).components;
const component = (type: string): RawComponent => {
  const found = components.find((c) => c.type === type);
  if (found === undefined) throw new Error(`component ${type} not in the package`);
  return found;
};

/**
 * A field's key relative to its component's `configPath`. The generated package currently carries
 * the FULL dotted path in every key (known defect 1 below); once that is fixed the keys become
 * relative. Asserting on the relative form keeps these tests about the model, not the defect.
 */
const rel = (c: RawComponent, key: string): string =>
  key.startsWith(`${c.configPath}.`) ? key.slice(c.configPath.length + 1) : key;
const relKeys = (type: string): string[] =>
  component(type).fields.map((f) => rel(component(type), f.key));

function fields(type: string, draft: Record<string, unknown>, version = 1) {
  return project(
    pkg,
    [{ type, instanceId: 'i1' }],
    { i1: draft as never },
    OBSERVED,
    version,
  ).sections.flatMap((s) => s.fields);
}

describe('file-mesh package: validity', () => {
  it('passes the load-time gates — the exact check the upload dialog runs', () => {
    // Regression for the upload rejection: ten `solace` fields declared version 4 while the
    // `solace` component declared [1,2,3] (gate 14, FIELD_VERSION_OUTSIDE_COMPONENT).
    const result = parseAndValidateSolutionPackage(JSON.stringify(RAW), {
      devMode: true,
      existingPackages: [],
    });
    expect(result.valid ? [] : result.issues).toEqual([]);
    expect(result.valid).toBe(true);
  });
});

describe('file-mesh package: shape', () => {
  it('declares ten components; only the source and sink are one-of-many roles', () => {
    expect(components.map((c) => [c.type, c.role, c.cardinality])).toEqual([
      ['file-source', 'source', 'one-of-many'],
      ['file-sink', 'sink', 'one-of-many'],
      ['cache', 'none', 'zero-or-more'],
      ['server', 'none', 'zero-or-more'],
      ['spring-boot-admin', 'none', 'zero-or-more'],
      ['spring-cloud-stream', 'none', 'zero-or-more'],
      ['solace', 'none', 'zero-or-more'],
      ['application', 'none', 'zero-or-more'],
      ['logging', 'none', 'zero-or-more'],
      ['management', 'none', 'zero-or-more'],
    ]);
  });

  it('holds the stream bindings (input-0 / output-0) in spring-cloud-stream, not in per-role binding components', () => {
    expect(relKeys('spring-cloud-stream')).toEqual([
      'bindings.input-0.destination',
      'bindings.input-0.binder',
      'bindings.output-0.destination',
      'bindings.output-0.binder',
    ]);
    expect(components.some((c) => /BINDING/.test(c.type))).toBe(false);
  });

  it('chooses a connector by a per-connector `enabled` flag (default off), never a discriminator selector', () => {
    for (const type of ['file-source', 'file-sink']) {
      const c = component(type);
      for (const connector of ['gcs', 'sftp_settings', 'ftp_settings']) {
        const flag = c.fields.find((f) => rel(c, f.key) === `${connector}.enabled`);
        expect(flag?.default, `${type} ${connector}.enabled`).toBe(0);
      }
      expect(c.fields.some((f) => f.discriminator === true)).toBe(false);
    }
  });

  it('keeps source-only and sink-only settings apart', () => {
    const source = relKeys('file-source');
    const sink = relKeys('file-sink');
    expect(source).toContain('scheduler.restart_time_sec');
    expect(sink).not.toContain('scheduler.restart_time_sec');
    expect(sink).toContain('gcs.enableBulkComposeStrategy');
    expect(source).not.toContain('gcs.enableBulkComposeStrategy');
    expect(sink).toContain('file_owner_permissions.override_username');
    expect(source.some((k) => k.includes('file_owner_permissions'))).toBe(false);
  });

  it('has unique keys within every component, case-insensitively (Q-B)', () => {
    for (const c of components) {
      const keys = c.fields.map((f) => f.key.toLowerCase());
      expect(new Set(keys).size, c.type).toBe(keys.length);
    }
  });
});

describe('file-mesh package: secrets (I6)', () => {
  it('declares every password field as a secret, in every component', () => {
    for (const c of components) {
      for (const f of c.fields) {
        if (/(^|\.)(client-)?password$/.test(f.key))
          expect(f.type, `${c.type} ${f.key}`).toBe('secret');
      }
    }
  });

  it('renders every password as a masked secret control in the projected view', () => {
    for (const type of ['file-source', 'file-sink', 'solace']) {
      const passwords = fields(type, {}, type === 'solace' ? 4 : 1).filter((f) =>
        /password$/.test(f.key),
      );
      expect(passwords.length, type).toBeGreaterThan(0);
      for (const f of passwords) {
        expect(f.controlKind, `${type} ${f.key}`).toBe('secret');
        expect(f.masked, `${type} ${f.key}`).toBe(true);
      }
    }
  });
});

describe('file-mesh package: broker connection bindings (UI-D28, version 4)', () => {
  const solace = component('solace');
  const selectors = solace.fields.filter((f) => f.optionsSource !== undefined);

  it('selects its two brokers from the connection catalog, as virtual required fields', () => {
    expect(selectors.map((f) => f.key)).toEqual(['solaceManagementBroker', 'solaceDataBroker']);
    for (const s of selectors) {
      expect(s.virtual).toBe(true);
      expect(s.required).toBe(true);
      expect(s.optionsSource).toEqual({
        kind: 'sm-catalog',
        entity: 'connection',
        connectionType: 'solace-broker',
      });
    }
  });

  it('derives host / vpn / username / password from the chosen broker, eight fields in all', () => {
    const bound = solace.fields.filter((f) => f.valueFrom !== undefined);
    expect(bound).toHaveLength(8);
    const selectorKeys = new Set(selectors.map((s) => s.key));
    for (const f of bound) expect(selectorKeys.has(f.valueFrom?.ref ?? ''), f.key).toBe(true);
    expect(
      bound.filter((f) => f.valueFrom?.attribute === 'password').every((f) => f.type === 'secret'),
    ).toBe(true);
  });

  it('is the only component that declares version 4', () => {
    expect(components.filter((c) => c.version.includes(4)).map((c) => c.type)).toEqual(['solace']);
  });
});

describe('file-mesh package: cache component', () => {
  it('offers REDIS_URL only from package version 3, and asks for it only when CACHE_PROVIDER is redis', () => {
    // REDIS_URL is declared for version [3] only: absent entirely at version 2.
    expect(fields('cache', { CACHE_PROVIDER: 'redis' }, 2).some((f) => f.key === 'REDIS_URL')).toBe(
      false,
    );
    const redisUrl = (draft: Record<string, unknown>) =>
      fields('cache', draft, 3).find((f) => f.key === 'REDIS_URL');
    expect(redisUrl({ CACHE_PROVIDER: 'redis' })?.visible).toBe(true);
    expect(redisUrl({ CACHE_PROVIDER: 'redis' })?.validations.map((v) => v.code)).toEqual([
      'REQUIRED',
    ]);
    expect(redisUrl({ CACHE_PROVIDER: 'memory' })?.visible).toBe(false);
    expect(redisUrl({ CACHE_PROVIDER: 'memory' })?.validations).toEqual([]);
  });
});

describe('file-mesh package: KNOWN DEFECTS of the generated package (reported, not worked around)', () => {
  // DEFECT 1 — every field's key already carries its component's full `configPath`
  // (150 of 156 fields), and the engine prepends `configPath` again, so the rendered property is
  // e.g. `file.source.file.source.sftp_settings.ip`. `configPath` is a REQUIRED schema property
  // ("property prefix SM writes under"), so keys must be RELATIVE to it, as in the package this
  // replaced (`configPath: file.source`, key `sftp_settings.ip`). Applying this package as it
  // stands would write properties the connector does not read. Fix belongs in the generator.
  it.fails(
    'renders each field at its own dotted path, never repeating the component configPath',
    () => {
      for (const c of components) {
        for (const f of fields(c.type, {}, c.version.at(-1) ?? 1)) {
          if (f.renderedPath === null) continue;
          expect(
            f.renderedPath.startsWith(`${c.configPath}.${c.configPath}.`),
            `${c.type} ${f.key} → ${f.renderedPath}`,
          ).toBe(false);
        }
      }
    },
  );

  // DEFECT 2 — package version 4 is declared, but only `solace` carries it, so an instance
  // configured against version 4 renders the broker settings and NOTHING else (no source, sink,
  // server, logging…). Either every component should declare 4, or version 4 should not be a
  // package version for the rest. Needs the package owner's intent.
  it.fails('renders every component at the latest declared package version', () => {
    const latest = Math.max(...(RAW as unknown as { packageVersion: number[] }).packageVersion);
    for (const c of components) expect(c.version, c.type).toContain(latest);
  });
});
