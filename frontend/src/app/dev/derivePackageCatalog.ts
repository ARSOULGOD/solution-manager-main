/**
 * DEV-only: the console-side half of a dropped package.
 *
 * A `SolutionPackage` is a CONFIG SCHEMA. It deliberately carries no
 * display name, no brand colour, no glyph, no one-line descriptor and no
 * instance — those are console concerns, and `data/solutions.ts` says so
 * for the baked example ("everything below is what a package deliberately
 * does not carry"). For a package nobody has curated a card for, this
 * module derives them.
 *
 * **The rule this file obeys.** Package-owned fields are passed through
 * UNCHANGED — `packageVersion`, `appliesTo`, `provenance`, `authoring`
 * counts, and the component types. Everything else is DERIVED here,
 * mechanically and deterministically, and each derivation says below what
 * it is standing in for. Nothing is fabricated that the console would
 * later mistake for fact: `published` stays empty rather than becoming
 * today's date, and the synthesized instance reports `lastSeen: 'never'`
 * — the exact posture `data/instances.ts` mandates ("never a fabricated
 * timestamp").
 *
 * **Nothing here may know about a specific solution.** `REQ-001`'s gate is
 * that a solution is onboarded from its package file with zero commits to
 * SM; a derivation table keyed on "cache" or "file-mesh" would be exactly
 * the per-solution code the gate forbids, dressed as presentation. Every
 * rule below is therefore shape-only.
 *
 * DEV-only: reached from `devPackageHandlers.ts`'s handlers and
 * `devPackageStore.ts`, both behind `main.tsx`'s literal
 * `if (import.meta.env.DEV)` branch.
 */
import {
  UNOBSERVED_REPLICA_HEALTH,
  unobservedRuntimeFacts,
} from '@shared/testdoubles/data/declaredInstance';
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import type { SolutionPackageSummary } from '@shared/testdoubles/data/solutions';

import type { LoadedPackage } from './parsePackage';

/**
 * `types.ts` declares `provenance.signature` REQUIRED, but an unsigned
 * package is the case `UI-D19`'s dev-mode admission exists for, so
 * `parsePackage.ts` admits one without it (`LoadedPackage`). An empty
 * string counts as absent: a package that declares `"signature": ""` has
 * not been signed, whatever the field's presence suggests.
 */
export function signatureOf(pkg: LoadedPackage): string | undefined {
  const { signature } = pkg.provenance;
  return signature !== undefined && signature.length > 0 ? signature : undefined;
}

// ---------------------------------------------------------------------------
// Derivations
// ---------------------------------------------------------------------------

/**
 * A stable 32-bit hash of the solution id (FNV-1a). Deterministic across
 * reloads and across machines, which is the whole requirement: the same
 * package must not change colour when you re-drop it. A real avalanche
 * hash rather than a sum of char codes, so `db-designer` and
 * `db-designer-2` land at unrelated hues instead of adjacent ones.
 */
function hashSolutionId(solution: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < solution.length; index += 1) {
    hash ^= solution.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

/**
 * Saturation and lightness are FIXED; only the hue varies with the id.
 * That is what keeps a generated colour in family with the curated ones
 * (`#015B82`, `#3A4880`, `#2E6E4E` are all deep, ~40-50% saturated, ~30%
 * light) and — because lightness never rises — keeps `getContrastText()`
 * resolving to the same light foreground for every generated card, so no
 * derived colour can produce an unreadable one.
 */
const CARD_SATURATION = 0.46;
const CARD_LIGHTNESS = 0.31;

function hslToHex(hue: number, saturation: number, lightness: number): string {
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const second = chroma * (1 - Math.abs(((hue / 60) % 2) - 1));
  const match = lightness - chroma / 2;
  const sextant = Math.floor(hue / 60) % 6;
  const rgb = (
    [
      [chroma, second, 0],
      [second, chroma, 0],
      [0, chroma, second],
      [0, second, chroma],
      [second, 0, chroma],
      [chroma, 0, second],
    ] as const
  )[sextant] ?? [0, 0, 0];
  const channels = rgb
    .map((channel) =>
      Math.round((channel + match) * 255)
        .toString(16)
        .padStart(2, '0'),
    )
    .join('');
  // Assembled, never written as a literal — raw colour literals are banned
  // outside `shared/theme` (design §8.1), and this is generated data, not
  // a styling decision.
  return `#${channels}`;
}

/** Deterministic card colour for a package the console has no curated one for. */
export function deriveColor(solution: string): string {
  return hslToHex(hashSolutionId(solution) % 360, CARD_SATURATION, CARD_LIGHTNESS);
}

/**
 * `SolutionGlyph` (`solutions/icons.tsx`) resolves an unknown name to
 * `PackageGlyphIcon`. A dropped package gets that default deliberately:
 * picking a glyph per package would mean inventing a taxonomy the package
 * does not declare, and the generic package mark is the honest one.
 */
export const DEFAULT_PACKAGE_GLYPH = 'package';

/**
 * The card's one-line descriptor. No package field carries it (`§16 Q10`),
 * and the curated cards' copy ("Distributed cache · Spring Boot") is
 * editorial — nothing can infer it.
 *
 * **The exact mapping, which `derivePackageCatalog.test.ts` asserts:** the
 * declared component types, in declaration order, joined with ` · `. That
 * is the only fact about its own shape a package states. Nothing is
 * humanized, title-cased, pluralized or looked up in a table of known
 * solution names — see the module header on why a lookup table would be
 * the wrong shape here. It degrades honestly: a one-component package
 * reads `db-connector`, a three-component one `source · flow · sink`, and
 * a package declaring no components never gets this far (`parsePackage.ts`
 * rejects it).
 */
export function deriveKind(componentTypes: readonly string[]): string {
  return componentTypes.join(' · ');
}

/**
 * Display name. Nothing mechanical can know that `db-designer` is styled
 * "DB Designer", so this casts each token of the id to Title Case and
 * stops there: a deterministic rendering of what the package DID declare,
 * never a guess at copy it did not.
 */
export function deriveName(solution: string): string {
  return solution
    .split(/[-_.\s]+/)
    .filter((word) => word.length > 0)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

/**
 * The card's telemetry line: one label per declared metric TRANSPORT
 * (`SourceKind`, `design.md` §3.4), de-duplicated, in declaration order. A
 * package declaring no metrics says so rather than borrowing a
 * plausible-looking default from another solution's card.
 */
const TELEMETRY_LABEL: Readonly<Record<string, string>> = {
  http_json: 'HTTP poll · JSON',
  prometheus: 'Prometheus scrape',
  solace_event: 'Broker push · Solace events',
  exec: 'Exec probe',
  jdbc_query: 'JDBC query',
};

export const NO_TELEMETRY_DECLARED = 'No telemetry declared';

export function deriveTelemetryMode(pkg: LoadedPackage): string {
  const labels: string[] = [];
  for (const component of pkg.components) {
    for (const metric of component.metrics ?? []) {
      // An unrecognised transport is reported as the raw kind, not dropped
      // — `SourceKind` is a closed vocabulary today, and a package using a
      // member this table has not caught up with should still say so.
      const label = TELEMETRY_LABEL[metric.source.kind] ?? metric.source.kind;
      if (!labels.includes(label)) {
        labels.push(label);
      }
    }
  }
  return labels.length === 0 ? NO_TELEMETRY_DECLARED : labels.join(' · ');
}

// ---------------------------------------------------------------------------
// The two console objects a dropped package stands up
// ---------------------------------------------------------------------------

/**
 * The instance id every dev route uses for this package — ONE derivation
 * behind all four routes (`/cp/solutions`, `/dp/solutions/:id/instances`,
 * `/dp/instances/:id`, `/dp/instances/:id/designer`), so a rename in the
 * package file moves them together and cannot leave one of them stranded.
 */
export function devInstanceIdFor(solution: string): string {
  return `${solution}-dev-01`;
}

export function derivePackageCatalog(pkg: LoadedPackage): SolutionPackageSummary {
  const componentTypes = pkg.components.map((component) => component.type);
  const signature = signatureOf(pkg);

  return {
    // ---- Package-owned: passed through unchanged ----
    // `id` is the solution id, which is also `solutionId` on the instance
    // below — see `devInstanceIdFor()` on why that matters.
    id: pkg.solution,
    solution: pkg.solution,
    packageVersion: pkg.packageVersion,
    appliesTo: pkg.appliesTo,
    provenance: {
      author: pkg.provenance.author,
      ...(signature === undefined ? {} : { signature }),
    },
    authoring: {
      reviewedRows: pkg.authoring.reviewedRows,
      totalRows: pkg.authoring.totalRows,
      // Not a package field (`§16 Q10`) — `source` describes the PROCESS
      // that produced the schema rows, which is outside the file. States
      // how this card came to exist instead of guessing at a process.
      source: 'loaded package file',
    },
    components: componentTypes,

    // ---- Console-only: derived here ----
    name: deriveName(pkg.solution),
    color: deriveColor(pkg.solution),
    icon: DEFAULT_PACKAGE_GLYPH,
    kind: deriveKind(componentTypes),
    telemetryMode: deriveTelemetryMode(pkg),
    // The one instance `derivePackageInstance()` stands up below. This is
    // the DERIVATION-TIME value; the handlers serving `/cp/solutions` are
    // authoritative and recompute it from the live instance list
    // (`withInstanceCounts`, FE-120), so declaring or undeclaring against
    // this package moves the number here too.
    instanceCount: 1,
    // A package admitted under `UI-D19` dev-mode admission is usable by
    // definition; entitlement is a control-plane fact with no package field.
    entitled: true,
    // No package field carries a publication date, and `data/instances.ts`'s
    // own rule forbids fabricating one. Nothing renders this today.
    published: '',
    // `UI-D19`: unsigned means dev-mode admission. The package carries a
    // signature or it does not; nothing else decides this.
    devMode: signature === undefined,
  };
}

/**
 * The single instance a dropped package stands up, so the catalog card
 * leads somewhere. Every unknowable runtime fact is stated as unknown
 * rather than invented — see `@shared/testdoubles/data/declaredInstance`,
 * which holds that posture for both this and an operator-declared instance.
 *
 * No `declaredVersion` is passed: `appliesTo` is a RANGE of MI versions
 * the package applies to, not a version this instance runs, so there is
 * no declaration to report.
 *
 * `driver`/`locus` are closed vocabularies with no "unknown" member, so
 * they carry the same values the baked example instance uses.
 *
 * `packageVersion` is the last entry of the package's own ascending list —
 * see the field itself.
 */
export function derivePackageInstance(pkg: LoadedPackage): InstanceRow {
  const id = devInstanceIdFor(pkg.solution);
  return {
    id,
    name: id,
    solutionId: pkg.solution,
    // The LAST version the package declares (`packageVersion` is ascending,
    // FE-118). Nothing in a package file says which version a synthesized
    // instance should be configured against, and "the newest one this
    // package serves" is the only reading that does not require inventing
    // a history the file does not carry.
    packageVersion: pkg.packageVersion.at(-1) ?? '',
    env: 'DEV',
    // The package declares no owner. Dev-only synthesis places it in the
    // canonical fixture's explicit sandbox branch rather than inventing a
    // second hierarchy vocabulary (`D20`).
    org: 'Acme Financial',
    subOrg: 'Sandbox',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [{ name: `${id}-0`, health: UNOBSERVED_REPLICA_HEALTH }],
    ...unobservedRuntimeFacts(),
  };
}
