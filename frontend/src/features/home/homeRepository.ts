/**
 * `HomeRepository` (task 033, §6.0.5, §9.2, UI §A.4; integration plan 004).
 *
 * CONTRACT (re-checked 2026-10-08 against the merged `contracts/openapi/console.yaml`,
 * supersedes plan 002's Task 2 / decisions D-1, D-2 — see
 * `docs/plans/integration/reports/004-task-report.md` for the full Task 0 finding):
 *
 * - `GET /cp/home/summary` → `HomeSummary { solutions: [{ solutionId, name,
 *   instanceCount, byHealth?, stateVersion }] }`. No top-level
 *   `configStagedNotApplied` field anywhere on the wire — **D-2 is still open**:
 *   it renders `null` / "—", never a fabricated number.
 * - `GET /cp/home/attention` → `HomeAttention { items: [{ instanceId,
 *   placementId?, reason, state?, stateVersion }] }`. No `instanceName` or
 *   `solutionId` on the item — **D-1 option B** (resolve via `getInstance`,
 *   `GET /dp/instances/{instanceId}`), not option A. A resolution failure for
 *   one item degrades that row to a non-navigable one; it never fabricates a
 *   link or drops the item from the feed.
 *
 * A count is `null` only when the wire carries no source for it (G10, §A.4's
 * "an unknown value renders — , never 0"); every other count is a plain sum
 * over what the wire did send.
 */
import type { PlaneClient } from '@shared/api';

export interface HomeSummary {
  solutionsManaged: number | null;
  instancesDeclared: number | null;
  needAttentionNow: number | null;
  /** D-2: no wire field carries this yet. Always `null` until one does. */
  configStagedNotApplied: number | null;
  /**
   * INVENTED (task 038, §16 Q10 — no schema field carries this either).
   * No wire source exists today, so this is always `undefined` in the real
   * adapter — kept on the type only because `HomePage`/`StatStrip` branch on
   * it, and a future partial-state signal would slot in here without a
   * component change.
   */
  partial?: { unreportingHost: string } | null;
}

export type AttentionSeverity = 'err' | 'warn' | 'info' | 'idle' | 'ok';

export interface AttentionItem {
  id: string;
  severity: AttentionSeverity;
  title: string;
  detail: string;
  instanceId: string;
  /** Absent when the instance lookup used to build it (D-1 option B) failed. */
  href?: string;
}

export interface HomeRepository {
  summary(signal?: AbortSignal): Promise<HomeSummary>;
  attention(signal?: AbortSignal): Promise<AttentionItem[]>;
}

// ---- wire shapes (contracts/openapi/console.yaml: HomeSummary, HomeAttention, Instance) ----

interface WireHomeSolution {
  solutionId: string;
  name: string;
  instanceCount: number;
  byHealth?: Record<string, number>;
  stateVersion: unknown;
}

interface WireHomeSummary {
  solutions: WireHomeSolution[];
}

type AttentionReason = 'health' | 'apply-indeterminate' | 'config-diverged';

interface WireHomeAttentionItem {
  instanceId: string;
  placementId?: string;
  reason: AttentionReason;
  state?: string;
  stateVersion: unknown;
}

interface WireHomeAttention {
  items: WireHomeAttentionItem[];
}

interface WireInstance {
  instanceId: string;
  name: string;
  solutionId: string;
}

// The exact pair the plan's Task 1 names explicitly as the "err-tone
// states" that feed the stat strip's "Need attention now" tile — this is a
// counter policy, independent of the per-row severity badge below, which
// follows STATE-VOCABULARY.md §2's own (differently-grouped) tone tables.
const ERR_TONE_COUNTER_STATES = ['RED', 'unreachable'] as const;

function toSummary(wire: WireHomeSummary): HomeSummary {
  let instancesDeclared = 0;
  let needAttentionNow = 0;

  for (const solution of wire.solutions) {
    instancesDeclared += solution.instanceCount;
    for (const key of ERR_TONE_COUNTER_STATES) {
      needAttentionNow += solution.byHealth?.[key] ?? 0;
    }
  }

  return {
    solutionsManaged: wire.solutions.length,
    instancesDeclared,
    needAttentionNow,
    // D-2: no wire source — never a fabricated number.
    configStagedNotApplied: null,
  };
}

// Per-row severity badge for the attention feed. Combines the three tone
// tables STATE-VOCABULARY.md §2 assigns to this vocabulary's members
// (HEALTH, GOV_HEALTH, NOTFOUND) — `reason: 'health'` carries one of these
// nine wire states; `apply-indeterminate` and `config-diverged` carry none,
// so they get a fixed severity (CONFIG's `diverged` entry is `err`;
// "indeterminate" has no table entry of its own and is treated as `warn`,
// a judgment call recorded here rather than silently invented elsewhere).
const HEALTH_STATE_SEVERITY: Record<string, AttentionSeverity> = {
  GREEN: 'ok',
  RED: 'err',
  RESTART_EXPECTED: 'info',
  INACTIVE_REPLICA: 'idle',
  NOT_APPLICABLE: 'idle',
  unreachable: 'warn',
  not_exposed: 'warn',
  missing: 'err',
  bootstrap: 'info',
};

function severityOf(reason: AttentionReason, state: string | undefined): AttentionSeverity {
  if (reason === 'config-diverged') {
    return 'err';
  }
  if (reason === 'apply-indeterminate') {
    return 'warn';
  }
  // reason === 'health'
  if (state !== undefined && state in HEALTH_STATE_SEVERITY) {
    return HEALTH_STATE_SEVERITY[state] as AttentionSeverity;
  }
  return 'warn';
}

function reasonLabel(reason: AttentionReason): string {
  switch (reason) {
    case 'health':
      return 'Health';
    case 'apply-indeterminate':
      return 'Apply status indeterminate';
    case 'config-diverged':
      return 'Configuration diverged';
  }
}

function stateLabel(state: string | undefined): string | undefined {
  if (state === undefined) {
    return undefined;
  }
  // Reuses the enum's own casing as a readable fallback label — this is
  // display copy, not a second vocabulary; no table lookup is invented here.
  return state.replace(/_/g, ' ').toLowerCase();
}

function buildHref(wireInstance: WireInstance | undefined, instanceId: string): string | undefined {
  if (wireInstance === undefined) {
    return undefined;
  }
  // Matches `routes.ts`'s `instanceCategory` pattern
  // (`/solutions/:solutionId/instances/:instanceId/:category`) — not
  // imported from there (`shared/app` boundary; Home only links into
  // Governance/Designer, it does not own that route table) and reconstructed
  // here as a plain template, same way the pre-integration fixture built it.
  return `/solutions/${wireInstance.solutionId}/instances/${instanceId}/governance`;
}

async function resolveInstance(
  dpClient: PlaneClient,
  instanceId: string,
  signal: AbortSignal | undefined,
): Promise<WireInstance | undefined> {
  try {
    return await dpClient.get<WireInstance>(`/instances/${instanceId}`, signal !== undefined ? { signal } : {});
  } catch {
    // A single unresolved instance (404, 403, or a transient failure)
    // degrades that one row to a non-navigable one. It never fails the
    // whole feed and never fabricates a link (D-1 option B's failure mode).
    return undefined;
  }
}

async function toAttentionItems(
  wire: WireHomeAttention,
  dpClient: PlaneClient,
  signal: AbortSignal | undefined,
): Promise<AttentionItem[]> {
  const resolved = await Promise.all(
    wire.items.map((item) => resolveInstance(dpClient, item.instanceId, signal)),
  );

  return wire.items.map((item, index) => {
    const instance = resolved[index];
    const displayName = instance?.name ?? item.instanceId;
    const label = stateLabel(item.state);
    const title = `${displayName} · ${reasonLabel(item.reason)}`;
    const detail = label !== undefined ? `State: ${label}.` : reasonLabel(item.reason);
    const href = buildHref(instance, item.instanceId);

    // `exactOptionalPropertyTypes`: `href` must be OMITTED, not set to
    // `undefined`, when D-1 option B's lookup didn't resolve — spreading a
    // conditional object is how this codebase expresses that (same pattern
    // `opts()` above uses for `signal`).
    return {
      id: `${item.instanceId}-${item.reason}`,
      severity: severityOf(item.reason, item.state),
      title,
      detail,
      instanceId: item.instanceId,
      ...(href !== undefined ? { href } : {}),
    };
  });
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createHomeRepository(cpClient: PlaneClient, dpClient: PlaneClient): HomeRepository {
  return {
    async summary(signal) {
      const wire = await cpClient.get<WireHomeSummary>('/home/summary', opts(signal));
      return toSummary(wire);
    },
    async attention(signal) {
      const wire = await cpClient.get<WireHomeAttention>('/home/attention', opts(signal));
      return toAttentionItems(wire, dpClient, signal);
    },
  };
}
