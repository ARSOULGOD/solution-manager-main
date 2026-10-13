/**
 * Solution catalog fixture (task 033, UI §A.5, §A.13). Ported from
 * `docs/design/frontend/reference/app/data.jsx`'s `SOLUTIONS` — package
 * metadata (`name`, `packageVersion`, `appliesTo`, `components`) is
 * SPECIFIED shape per `UI-D11`; `authoring.reviewedRows`/`totalRows` are
 * INVENTED (§16 Q10 — "no package schema field carries these counts").
 */

export interface SolutionPackageSummary {
  id: string;
  solution: string;
  name: string;
  color: string;
  icon: string;
  kind: string;
  /**
   * Every version this package serves, ascending (FE-118) — the array
   * `SolutionPackage.packageVersion` declares. Restated here rather than
   * imported: `shared/**` may not import `contracts/**` (`eslint.config.js`
   * boundaries), the same hand-sync posture `InstanceRow.packageVersion`
   * takes.
   */
  packageVersion: readonly (string | number)[];
  appliesTo: string;
  provenance: { author: string; signature?: string };
  /** INVENTED — no package schema field carries these (§16 Q10). */
  authoring: { reviewedRows: number; totalRows: number; source: string };
  components: readonly string[];
  instanceCount: number;
  entitled: boolean;
  telemetryMode: string;
  published: string;
  devMode?: boolean;
}

export const SOLUTIONS: readonly SolutionPackageSummary[] = [
  {
    id: 'cache',
    solution: 'cache',
    name: 'Cache Engine',
    color: '#015B82',
    icon: 'bolt',
    kind: 'Distributed cache · Spring Boot',
    packageVersion: [6, 7],
    appliesTo: '>=2.2 <2.5',
    provenance: { author: 'solace', signature: '3af9…c210' },
    authoring: { reviewedRows: 1494, totalRows: 1494, source: 'curated + contract-tested' },
    components: ['cache-engine', 'management', 'telemetry-http'],
    instanceCount: 12,
    entitled: true,
    telemetryMode: 'HTTP poll · actuator',
    published: '2026-08-04',
  },
  {
    id: 'cache-nightly',
    solution: 'cache',
    name: 'Cache Engine · nightly',
    color: '#8a5a1e',
    icon: 'beaker',
    kind: 'Distributed cache · unsigned build',
    packageVersion: ['dev-2026.08.17'],
    appliesTo: 'dev',
    provenance: { author: 'dev-mode' },
    authoring: { reviewedRows: 0, totalRows: 1494, source: 'extracted, unreviewed' },
    components: ['cache-engine', 'management', 'telemetry-http'],
    instanceCount: 1,
    entitled: true,
    telemetryMode: 'HTTP poll · actuator',
    published: '2026-08-17',
    devMode: true,
  },
] as const;

/**
 * Instance counts recomputed from the live instance list (FE-120).
 *
 * `instanceCount` was a hardcoded fixture number — Cache said 12 for ever,
 * so declaring a thirteenth moved the grid and Home's `StatStrip` (which
 * already derives its count) but left the Solutions card and the sidebar
 * reading 12. `cache-nightly` was worse: it claimed 1 instance with an
 * empty grid, wrong before anything was declared.
 *
 * ⚠️ **Keyed on the CARD ID, never `card.solution`.** The grid behind a
 * card is `/dp/solutions/:solutionId/instances`, which filters on the card
 * id — so counting any other way would give a number that disagrees with
 * the grid the card links to. `cache` and `cache-nightly` are two cards
 * sharing one `solution`, which is exactly where the two readings diverge.
 *
 * Honest consequence: `cache-nightly` goes 1 → 0, because no instance
 * carries `solutionId: 'cache-nightly'`. That card was already lying.
 */
export function withInstanceCounts(
  cards: readonly SolutionPackageSummary[],
  instances: readonly { solutionId: string }[],
): SolutionPackageSummary[] {
  return cards.map((card) => ({
    ...card,
    instanceCount: instances.filter((instance) => instance.solutionId === card.id).length,
  }));
}
