/**
 * The console-only half of a dropped package. Two things these tests are
 * for: pinning the DERIVATION RULES so a card cannot start inventing
 * facts, and pinning the split — a package-owned field must arrive
 * unchanged, never "improved" on the way to the card.
 */
import { describe, expect, it } from 'vitest';

import {
  DEFAULT_PACKAGE_GLYPH,
  NO_TELEMETRY_DECLARED,
  deriveColor,
  deriveKind,
  deriveName,
  derivePackageCatalog,
  derivePackageInstance,
  deriveTelemetryMode,
  devInstanceIdFor,
  signatureOf,
} from './derivePackageCatalog';
import type { LoadedPackage } from './parsePackage';
import { TEST_PACKAGE, packageWith } from './testPackage';

const pkg = TEST_PACKAGE as unknown as LoadedPackage;
const load = (overrides: Record<string, unknown>) => packageWith(overrides) as LoadedPackage;

describe('derivePackageCatalog — package-owned fields pass through unchanged', () => {
  it('carries the package version, applies-to range, author and review counts verbatim', () => {
    const card = derivePackageCatalog(pkg);

    expect(card.packageVersion).toBe(pkg.packageVersion);
    expect(card.appliesTo).toBe(pkg.appliesTo);
    expect(card.provenance).toEqual({ author: 'acme', signature: 'ab12…cd34' });
    expect(card.authoring.reviewedRows).toBe(3);
    expect(card.authoring.totalRows).toBe(4);
    expect(card.components).toEqual(['widget-connector']);
  });

  it('keys the card by the solution id, which is also the instance list key', () => {
    const card = derivePackageCatalog(pkg);

    expect(card.id).toBe('widget-mesh');
    expect(card.solution).toBe('widget-mesh');
    expect(derivePackageInstance(pkg).solutionId).toBe(card.id);
  });
});

describe('derivePackageCatalog — devMode follows the signature and nothing else', () => {
  it('is false for a signed package', () => {
    expect(derivePackageCatalog(pkg).devMode).toBe(false);
  });

  it('is true when provenance carries no signature', () => {
    const unsigned = load({ provenance: { author: 'acme' } });

    expect(derivePackageCatalog(unsigned).devMode).toBe(true);
    expect(derivePackageCatalog(unsigned).provenance).toEqual({ author: 'acme' });
  });

  it('treats an empty signature string as unsigned', () => {
    const unsigned = load({ provenance: { author: 'acme', signature: '' } });

    expect(signatureOf(unsigned)).toBeUndefined();
    expect(derivePackageCatalog(unsigned).devMode).toBe(true);
  });
});

describe('deriveKind — the exact mapping from component types', () => {
  // The rule, pinned: declared component TYPES, in declaration order,
  // joined with " · ". No lookup table of known solutions, no humanizing,
  // no pluralized count — see the module header on why.
  it.each([
    [['widget-connector'], 'widget-connector'],
    [['source', 'flow', 'sink'], 'source · flow · sink'],
    [['cache-engine', 'management'], 'cache-engine · management'],
  ])('%j -> %s', (types, expected) => {
    expect(deriveKind(types)).toBe(expected);
  });

  it('is what the card carries', () => {
    expect(derivePackageCatalog(pkg).kind).toBe('widget-connector');
  });
});

describe('deriveColor — deterministic, in family, and readable', () => {
  it('is stable for the same id across calls', () => {
    expect(deriveColor('widget-mesh')).toBe(deriveColor('widget-mesh'));
  });

  it('differs between ids that differ only by a suffix', () => {
    expect(deriveColor('db-designer')).not.toBe(deriveColor('db-designer-2'));
  });

  it('is always a 6-digit hex triple', () => {
    for (const id of ['a', 'widget-mesh', 'db-designer', 'zzz-really-long-solution-id']) {
      expect(deriveColor(id)).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it('never rises above the fixed lightness, so a light foreground always contrasts', () => {
    // The relative-luminance ceiling that keeps `getContrastText()` on the
    // light foreground: every derived colour is built at L=0.31, so its
    // brightest possible channel mix stays well under mid-grey.
    for (const id of ['a', 'b', 'c', 'widget-mesh', 'file-mesh', 'db-designer']) {
      const hex = deriveColor(id);
      const channels = [1, 3, 5].map((at) => Number.parseInt(hex.slice(at, at + 2), 16) / 255);
      const [red = 0, green = 0, blue = 0] = channels;
      // Perceived brightness (ITU-R BT.601), the same rough measure MUI's
      // own contrast-text decision uses.
      expect(0.299 * red + 0.587 * green + 0.114 * blue).toBeLessThan(0.5);
    }
  });
});

describe('deriveName and the default glyph', () => {
  it.each([
    ['widget-mesh', 'Widget Mesh'],
    ['db_designer', 'Db Designer'],
    ['cache', 'Cache'],
  ])('%s -> %s', (id, expected) => {
    expect(deriveName(id)).toBe(expected);
  });

  it('uses the generic package glyph, which SolutionGlyph resolves by falling back', () => {
    expect(derivePackageCatalog(pkg).icon).toBe(DEFAULT_PACKAGE_GLYPH);
    // Not one of `SolutionGlyph`'s known names — that is the point.
    expect(['bolt', 'files', 'beaker']).not.toContain(DEFAULT_PACKAGE_GLYPH);
  });
});

describe('deriveTelemetryMode — from the declared metric transports', () => {
  it('labels the declared transport', () => {
    expect(deriveTelemetryMode(pkg)).toBe('HTTP poll · JSON');
  });

  it('says so when the package declares no metrics', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    delete components[0]?.['metrics'];

    expect(deriveTelemetryMode(load({ components }))).toBe(NO_TELEMETRY_DECLARED);
  });

  it('de-duplicates and keeps declaration order across transports', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    components[0]!['metrics'] = [
      { name: 'a', source: { kind: 'solace_event' } },
      { name: 'b', source: { kind: 'http_json' } },
      { name: 'c', source: { kind: 'solace_event' } },
    ];

    expect(deriveTelemetryMode(load({ components }))).toBe(
      'Broker push · Solace events · HTTP poll · JSON',
    );
  });
});

describe('derivePackageCatalog — nothing is fabricated', () => {
  it('leaves `published` empty rather than stamping today', () => {
    expect(derivePackageCatalog(pkg).published).toBe('');
  });

  it('names the source of the review counts as the file itself', () => {
    expect(derivePackageCatalog(pkg).authoring.source).toBe('loaded package file');
  });
});

describe('derivePackageInstance — states the unknown as unknown', () => {
  it('derives the id from the solution, once, for every route to share', () => {
    const instance = derivePackageInstance(pkg);

    expect(instance.id).toBe('widget-mesh-dev-01');
    expect(instance.id).toBe(devInstanceIdFor('widget-mesh'));
    expect(instance.name).toBe(instance.id);
  });

  it('reports never-observed rather than healthy-and-in-sync', () => {
    const instance = derivePackageInstance(pkg);

    expect(instance.healthRollup).toBe('not_applicable');
    expect(instance.configState).toBe('na');
    expect(instance.lastSeen).toBe('never');
    expect(instance.replicas.every((replica) => replica.health === 'not_applicable')).toBe(true);
  });

  it('reports the version as unverified, which does NOT block editing (UI-D12)', () => {
    const instance = derivePackageInstance(pkg);

    expect(instance.version.state).toBe('unverified');
    // `appliesTo` is a range, not a version this instance runs — neither
    // half of the version is claimed.
    expect(instance.version.declared).not.toContain(pkg.appliesTo);
  });

  it('counts exactly the one instance it stands up', () => {
    expect(derivePackageCatalog(pkg).instanceCount).toBe(1);
  });
});
