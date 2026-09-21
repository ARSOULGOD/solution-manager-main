/**
 * The dev loader's front door. Every rejection below is a file an operator
 * can genuinely produce — a zipped package, a signed bundle, a truncated
 * JSON, a package missing a field — and each has to come back as a STATED
 * reason rather than a stack trace or a silently empty Designer.
 */
import { describe, expect, it } from 'vitest';

import { parsePackageFile, parsePackageText, validatePackage } from './parsePackage';
import { TEST_PACKAGE, packageFile, packageWith } from './testPackage';

describe('parsePackage — accepting a valid package', () => {
  it('accepts a package declaring every field the projection flow reads', async () => {
    const result = await parsePackageFile(packageFile());

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.pkg.solution).toBe('widget-mesh');
    expect(result.pkg.components).toHaveLength(1);
    expect(result.pkg.components[0]?.fields).toHaveLength(3);
  });

  it('accepts a package with no signature — that is what dev-mode admission is for', () => {
    const unsigned = packageWith({ provenance: { author: 'acme' } });

    const result = validatePackage(unsigned);

    expect(result.ok).toBe(true);
  });

  it('accepts a package omitting fields nothing dereferences', () => {
    // `cardinality` and `loggerPrefix` are contract fields that neither
    // `project()` nor the catalog reads. A dev drop is not a conformance
    // gate — see the module header.
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    delete components[0]?.['cardinality'];
    delete components[0]?.['loggerPrefix'];

    expect(validatePackage(packageWith({ components })).ok).toBe(true);
  });
});

describe('parsePackage — malformed JSON', () => {
  it('rejects a truncated file with the parser error attached', async () => {
    const truncated = new File(['{ "solution": "widget-mesh"'], 'package.json', {
      type: 'application/json',
    });

    const result = await parsePackageFile(truncated);

    expect(result).toMatchObject({ ok: false, rejection: { code: 'malformed-json' } });
    if (result.ok) {
      return;
    }
    // The specific parser message is the whole point of parsing in the
    // browser — asserted as non-empty, not verbatim, since the text is the
    // JS engine's.
    expect(result.rejection.detail).toBeTruthy();
  });

  it('rejects an empty file', () => {
    expect(parsePackageText('')).toMatchObject({
      ok: false,
      rejection: { code: 'malformed-json' },
    });
  });

  it('rejects valid JSON that is not an object', () => {
    // `[]` and `"text"` parse fine; neither is a package.
    expect(parsePackageText('[]')).toMatchObject({
      ok: false,
      rejection: { code: 'invalid-package' },
    });
    expect(parsePackageText('"a package"')).toMatchObject({
      ok: false,
      rejection: { code: 'invalid-package' },
    });
  });
});

describe('parsePackage — structurally invalid packages', () => {
  it.each([
    ['solution', { solution: undefined }, 'solution'],
    // Scalars of BOTH admissible member types are rejected: the array is
    // the shape now (FE-118), and a bare `2` is the exact thing every
    // pre-FE-118 package file carried.
    ['packageVersion as a bare number', { packageVersion: 2 }, 'packageVersion'],
    ['packageVersion as a bare string', { packageVersion: '2' }, 'packageVersion'],
    ['packageVersion as an empty array', { packageVersion: [] }, 'packageVersion'],
    ['packageVersion holding a non-scalar', { packageVersion: [{ v: 2 }] }, 'packageVersion'],
    ['appliesTo', { appliesTo: undefined }, 'appliesTo'],
    ['provenance.author', { provenance: { signature: 'x' } }, 'provenance.author'],
    ['authoring counts', { authoring: { reviewedRows: 3 } }, 'authoring'],
    ['components', { components: undefined }, 'components'],
    ['a non-empty components list', { components: [] }, 'components'],
  ])('rejects a package missing %s and names the path', (_label, overrides, expectedPath) => {
    const result = validatePackage(packageWith(overrides));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.code).toBe('invalid-package');
    expect(result.rejection.detail).toContain(expectedPath);
  });

  it('ACCEPTS a package whose components and fields declare no version at all', () => {
    // Absent means "every version the package supports" — the rule that
    // keeps a third-party package written before FE-118 loadable. Nothing
    // in `TEST_PACKAGE` declares `version`.
    expect(validatePackage(packageWith({})).ok).toBe(true);
  });

  it('accepts a version list every member of which the package declares', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    components[0]!['version'] = [2];
    (components[0]?.['fields'] as Record<string, unknown>[])[1]!['version'] = [2];

    expect(validatePackage(packageWith({ components })).ok).toBe(true);
  });

  it('rejects a field scoped to a version the package does not serve', () => {
    // The field could never be selected, so it would never render — a
    // silent loss, which is exactly what this check exists to name.
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    (components[0]?.['fields'] as Record<string, unknown>[])[1]!['version'] = [9];

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('fields[1].version');
    expect(result.rejection.detail).toContain('9');
  });

  it('rejects a COMPONENT scoped to a version the package does not serve', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    components[0]!['version'] = ['nightly'];

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('components[0].version');
  });

  it('rejects an EMPTY version array — it belongs to no version and never renders', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    (components[0]?.['fields'] as Record<string, unknown>[])[0]!['version'] = [];

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('fields[0].version');
  });

  it('rejects a field declaring a type outside the closed vocabulary', () => {
    // `project()` looks the control kind up in a `Record` keyed by the
    // union — an unlisted member renders a control that does not exist.
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    (components[0]?.['fields'] as Record<string, unknown>[])[1]!['type'] = 'timestamp';

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('components[0].fields[1].type');
  });

  it('rejects an action declaring a kind outside the closed vocabulary', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    (components[0]?.['actions'] as Record<string, unknown>[])[0]!['kind'] = 'launch-missiles';

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('components[0].actions[0].kind');
  });

  it('rejects a metric with no source transport — the catalog card reads it', () => {
    const components = structuredClone(TEST_PACKAGE.components) as Record<string, unknown>[];
    delete (components[0]?.['metrics'] as Record<string, unknown>[])[0]!['source'];

    const result = validatePackage(packageWith({ components }));

    expect(result.ok).toBe(false);
    if (result.ok) {
      return;
    }
    expect(result.rejection.detail).toContain('components[0].metrics[0].source.kind');
  });

  it('rejects a signature that is present but not a string', () => {
    const result = validatePackage(packageWith({ provenance: { author: 'acme', signature: 7 } }));

    expect(result.ok).toBe(false);
  });
});

describe('parsePackage — archives', () => {
  it('rejects a .zip by name, before reading a byte of it', async () => {
    // Valid package CONTENT under a .zip name: the rejection must come
    // from the extension, so a real (binary) zip cannot instead surface as
    // a confusing JSON parse error.
    const result = await parsePackageFile(packageFile('widget-mesh.zip'));

    expect(result).toMatchObject({ ok: false, rejection: { code: 'zip-unsupported' } });
  });

  it('rejects a .sompkg by name', async () => {
    const result = await parsePackageFile(packageFile('widget-mesh-2.sompkg'));

    expect(result).toMatchObject({ ok: false, rejection: { code: 'sompkg-unsupported' } });
  });

  it('matches the extension case-insensitively', async () => {
    const result = await parsePackageFile(packageFile('WIDGET-MESH.SOMPKG'));

    expect(result).toMatchObject({ ok: false, rejection: { code: 'sompkg-unsupported' } });
  });

  it('every rejection carries an operator-facing sentence', async () => {
    for (const name of ['a.zip', 'b.sompkg', 'c.json']) {
      const file = name === 'c.json' ? new File(['{'], name) : packageFile(name);
      const result = await parsePackageFile(file);
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.rejection.message.length).toBeGreaterThan(0);
      }
    }
  });
});
