#!/usr/bin/env python3
"""Generate <solution>/package.generated.test.ts, a vitest contract test in the style of
frontend/contracts/packages/file-mesh/package.test.ts, for any package + profile.

    python3 gen_contract_test.py --package pkg.json --profile references/profiles/file-mesh.yaml \
        --out frontend/contracts/packages/_skill_file-mesh/package.generated.test.ts

The expectations are computed here (from the package and the profile) and embedded as JSON, so
the TypeScript is generic. The test must sit at frontend/contracts/packages/<dir>/ next to a copy
of the package (vitest's `contracts` project includes contracts/packages/**, and the imports are
relative to that depth). It never overwrites a hand-written package.test.ts.

Besides asserting, the test writes the console's OWN rendering of each role's defaults
(yamlFromFields from src/features/designer/yamlConfiguration.ts) to
$SM_SKILL_ARTIFACT_DIR/<solution>-<role>.rendered.json for the round-trip check.
"""
import argparse
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

PASSWORD_RX = r"(password|passwd|pwd)$"


def effective(declared, inherited):
    return list(inherited) if declared is None else [v for v in declared if v in inherited]


def build_expectations(pkg, prof):
    pv = pkg["packageVersion"]
    comps = pkg["components"]
    specs = {c["type"]: c for c in prof.get("components", [])}
    shape = [[c["type"], c.get("role") if c.get("role") is not None else None, c["cardinality"]] for c in comps]
    profile_shape = [[t, (specs[t].get("role") or "none"), specs[t]["cardinality"]] for t, _, _ in shape if t in specs]
    by_version = {}
    for v in pv:
        by_version[str(v)] = {}
        for c in comps:
            cv = effective(c.get("version"), pv)
            keys = [] if v not in cv else [f["key"] for f in c.get("fields", [])
                                             if v in effective(f.get("version"), cv)]
            by_version[str(v)][c["type"]] = keys

    # role separation, from the profile's component map
    role_patterns = {t: (s.get("match") or []) for t, s in specs.items()
                     if not s.get("shared") and (s.get("role") or "none") != "none"}
    misplaced = []
    for c in comps:
        for f in c.get("fields", []):
            if f.get("virtual"):
                continue
            full = f"{c['configPath']}.{f['key']}" if c.get("configPath") and specs.get(c["type"], {}).get(
                "key_style", prof.get("key_style")) == "relative-to-configPath" else f["key"]
            for t, pats in role_patterns.items():
                if t != c["type"] and common.best_match(pats, full):
                    misplaced.append([c["type"], f["key"], t])
    exclusive = []
    for rex in prof.get("role_exclusive") or []:
        for c in comps:
            if (c.get("role") or "none") == "none":
                for f in c.get("fields", []):
                    if common.matches(rex["match"], f["key"]) and not f.get("role"):
                        exclusive.append([c["type"], f["key"], rex["roles"]])

    when_cases, required_cases = [], []
    for c in comps:
        by_key = {f["key"]: f for f in c.get("fields", [])}
        cv = effective(c.get("version"), pv)
        for f in c.get("fields", []):
            fv = effective(f.get("version"), cv)
            if not fv:
                continue
            ver = fv[-1]
            w = f.get("when") or {}
            if w:
                k0, v0 = next(iter(w.items()))
                tgt = by_key.get(k0, {})
                other = None
                if isinstance(v0, bool):
                    other = not v0
                elif tgt.get("options"):
                    other = next((o for o in tgt["options"] if o != v0), None)
                when_cases.append({"component": c["type"], "key": f["key"], "version": ver, "on": w,
                                   "off": None if other is None else dict(w, **{k0: other})})
            if f.get("required") and f.get("default") is None and not f.get("valueFrom") and not w:
                required_cases.append({"component": c["type"], "key": f["key"], "version": ver})

    roles = prof.get("roles") or ["none"]
    render = []
    for role in roles:
        members = [c for c in comps if (c.get("role") or "none") in (role, "none")]
        role_comps = [c for c in members if (c.get("role") or "none") == role and role != "none"]
        vers = effective(role_comps[0].get("version"), pv) if role_comps else pv
        version = vers[-1] if vers else pv[-1]
        render.append({"role": role, "version": version,
                       "components": [c["type"] for c in members
                                      if version in effective(c.get("version"), pv)]})
    empty_cp = [c["type"] for c in comps if c.get("configPath", "") == "" and c.get("fields")]
    enums = [[c["type"], f["key"], f["options"], f.get("default")] for c in comps for f in c.get("fields", [])
             if f["type"] == "enum" and f.get("options")]
    return {"shape": shape, "profileShape": profile_shape, "byVersion": by_version, "misplaced": misplaced,
            "exclusiveInShared": exclusive, "when": when_cases, "required": required_cases, "render": render,
            "emptyConfigPath": empty_cp, "enums": enums, "packageVersions": pv}


TEMPLATE = r"""/**
 * GENERATED by solution-manager-yamlToPackage/scripts/gen_contract_test.py for `__SOLUTION__`.
 * Do not edit by hand; regenerate. Expectations below were computed from the package and the
 * mapping profile `__PROFILE__`. Known defects are pinned with `it.fails` and REPORTED, never
 * worked around: each passes while the defect exists and turns red once it is fixed.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { yamlFromFields, type YamlFieldRef } from '../../../src/features/designer/yamlConfiguration';
import { toFrontendPackage } from '../../solution-package';
import type { CanonicalSolutionPackage } from '../../solution-package/types';
import { parseAndValidateSolutionPackage } from '../../solution-package/validate';
import { project } from '../../view-model/project';
import type { ObservedState } from '../../view-model/types';

import RAW from './package.json';

const EXPECT = __EXPECT__ as const;

const canonical = RAW as unknown as CanonicalSolutionPackage;
const pkg = toFrontendPackage(canonical);
const OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };
interface RawField { key: string; type: string; default?: unknown; options?: unknown[]; virtual?: boolean }
interface RawComponent { type: string; role?: string | null; cardinality: string; configPath: string; fields: RawField[] }
const components = (RAW as unknown as { components: RawComponent[] }).components;

function view(type: string, draft: Record<string, unknown>, version: number) {
  return project(pkg, [{ type, instanceId: 'i1' }], { i1: draft as never }, OBSERVED, version).sections.flatMap(
    (s) => s.fields,
  );
}

describe('__SOLUTION__ package: validity', () => {
  it('passes the load-time gates the upload dialog runs (dev mode)', () => {
    const result = parseAndValidateSolutionPackage(JSON.stringify(RAW), { devMode: true, existingPackages: [] });
    expect(result.valid ? [] : result.issues).toEqual([]);
  });
});

describe('__SOLUTION__ package: shape', () => {
  it('declares the components, roles and cardinalities the mapping profile names', () => {
    const got = components
      .filter((c) => EXPECT.profileShape.some(([t]) => t === c.type))
      .map((c) => [c.type, c.role ?? 'none', c.cardinality]);
    expect(got).toEqual(EXPECT.profileShape);
  });

  it('has unique keys within every component, case-insensitively', () => {
    for (const c of components) {
      const keys = c.fields.map((f) => f.key.toLowerCase());
      expect(new Set(keys).size, c.type).toBe(keys.length);
    }
  });

  it('keeps role-only fields in their own role component', () => {
    expect(EXPECT.misplaced, 'field in component -> belongs to role component').toEqual([]);
  });

  it('keeps role-exclusive leaves out of shared components (unless given a field role)', () => {
    expect(EXPECT.exclusiveInShared).toEqual([]);
  });
});

describe('__SOLUTION__ package: secrets (I6)', () => {
  it('declares every password-like field as a secret, with no default value', () => {
    for (const c of components)
      for (const f of c.fields)
        if (/__PASSWORD_RX__/i.test(f.key.split('.').pop()!.replace(/[-_]/g, ''))) {
          expect(f.type, `${c.type} ${f.key}`).toBe('secret');
          expect(f.default ?? null, `${c.type} ${f.key} default`).toBeNull();
        }
  });

  it('projects every secret as a masked control whose value never appears', () => {
    for (const c of components) {
      const secrets = c.fields.filter((f) => f.type === 'secret').map((f) => f.key);
      if (secrets.length === 0) continue;
      const v = EXPECT.packageVersions[EXPECT.packageVersions.length - 1];
      for (const f of view(c.type, {}, v).filter((x) => secrets.includes(x.key))) {
        expect(f.controlKind, `${c.type} ${f.key}`).toBe('secret');
        expect(f.value.kind, `${c.type} ${f.key}`).not.toBe('value');
      }
    }
  });
});

describe('__SOLUTION__ package: enum, when, required', () => {
  it.each(EXPECT.enums.map((e) => [e[0], e[1], e] as const))('%s %s: default is one of its options', (_c, _k, e) => {
    const [, , options, dflt] = e;
    if (dflt !== null && dflt !== undefined) expect(options).toContain(dflt);
  });

  it.skipIf(EXPECT.when.length === 0).each(EXPECT.when.map((w) => [w.component, w.key, w] as const))(
    '%s %s: shown when its predicate holds, hidden otherwise',
    (_c, _k, w) => {
      const on = view(w.component, { ...w.on }, w.version).find((f) => f.key === w.key);
      expect(on?.visible).toBe(true);
      if (w.off !== null) {
        const off = view(w.component, { ...w.off }, w.version).find((f) => f.key === w.key);
        expect(off?.visible).toBe(false);
      }
    },
  );

  it.skipIf(EXPECT.required.length === 0).each(EXPECT.required.map((r) => [r.component, r.key, r] as const))(
    '%s %s: required with no default projects a REQUIRED validation',
    (_c, _k, r) => {
      const f = view(r.component, {}, r.version).find((x) => x.key === r.key);
      expect(f?.validations.some((v) => v.code === 'REQUIRED')).toBe(true);
    },
  );
});

describe('__SOLUTION__ package: version filtering through project()', () => {
  it.each(EXPECT.packageVersions.map((v) => [v]))('at package version %s, each component projects exactly its fields', (v) => {
    const expected = (EXPECT.byVersion as Record<string, Record<string, readonly string[]>>)[String(v)];
    for (const c of components) {
      const got = view(c.type, {}, v).map((f) => f.key).sort();
      expect(got, `${c.type} @v${v}`).toEqual([...expected[c.type]].sort());
    }
  });
});

describe('__SOLUTION__ package: KNOWN DEFECTS (reported, not worked around)', () => {
  // frontend/contracts/view-model/project.ts builds renderedPath as `${configPath}.${key}`, so an
  // empty configPath renders `.key`. The canonical engine (contracts/view-models/src/project.ts,
  // renderedPathFor) and its fixture fm-rendered-path-join-empty-configpath pin renderedPath === key.
  it.skipIf(EXPECT.emptyConfigPath.length === 0).fails(
    'renders a field of an empty-configPath component at its own key, with no leading dot',
    () => {
      const v = EXPECT.packageVersions[EXPECT.packageVersions.length - 1];
      for (const type of EXPECT.emptyConfigPath)
        for (const f of view(type, {}, v)) if (f.renderedPath !== null) expect(f.renderedPath).toBe(f.key);
    },
  );
});

describe('__SOLUTION__ package: render defaults with the console renderer (round-trip artifact)', () => {
  it.each(EXPECT.render.map((r) => [r.role, r] as const))('role %s', (_role, r) => {
    const refs: YamlFieldRef[] = [];
    for (const type of r.components)
      for (const field of view(type, {}, r.version)) refs.push({ instanceId: type, field });
    const out = yamlFromFields(refs);
    expect(out.text.length).toBeGreaterThan(0);
    const dir = process.env.SM_SKILL_ARTIFACT_DIR;
    if (dir) {
      mkdirSync(dir, { recursive: true });
      writeFileSync(
        join(dir, `__SOLUTION__-${r.role}.rendered.json`),
        JSON.stringify({ role: r.role, version: r.version, components: r.components, yaml: out.text,
          omittedSecrets: out.omittedSecrets, renderedPaths: refs.map((x) => [x.instanceId, x.field.key, x.field.renderedPath]) }, null, 2),
      );
    }
  });
});
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--package", required=True)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    if os.path.basename(args.out) == "package.test.ts":
        sys.exit("error: refusing to write package.test.ts (hand-written); use package.generated.test.ts")
    pkg = json.load(open(args.package, encoding="utf-8"))
    prof = yaml.load(open(args.profile, encoding="utf-8"), Loader=common.StrictLoader)
    exp = build_expectations(pkg, prof)
    ts = (TEMPLATE.replace("__EXPECT__", json.dumps(exp, indent=2))
          .replace("__SOLUTION__", pkg["solution"])
          .replace("__PROFILE__", os.path.basename(args.profile))
          .replace("__PASSWORD_RX__", PASSWORD_RX))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    open(args.out, "w", encoding="utf-8").write(ts)
    print(f"wrote {args.out}: {len(exp['when'])} when cases, {len(exp['required'])} required, "
          f"{len(exp['enums'])} enums, versions {exp['packageVersions']}, roles {[r['role'] for r in exp['render']]}")


if __name__ == "__main__":
    main()
