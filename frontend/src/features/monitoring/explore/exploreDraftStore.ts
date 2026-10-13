/**
 * Explore draft persistence (task 083, Dec `FE-D21` `DM-D9` `DM-D19`,
 * design §6.2/§26). The Explore **document** — everything `ExplorePage`
 * doesn't already keep in the URL (`FE-D6`) — lives in per-tab
 * `sessionStorage`, keyed by the short id the URL's own `?draft=` carries.
 * Range and instance selection are reload-safe and shareable via the URL
 * itself; this document is not — a link copied before the widget is saved
 * lands the recipient on an EMPTY Explore, never the sender's view. That
 * is `FE-D21`'s own accepted limit, not a bug this module works around.
 *
 * **Scope note.** `useIdleTimer.ts`'s `saveDraft`/`loadDraft` family is a
 * DIFFERENT mechanism for a DIFFERENT decision (`FE-D25`, Designer drafts
 * surviving an idle lock) whose own header says "Designer drafts ONLY.
 * Never metrics, Explore, or anything else" — so this module is a
 * deliberate sibling, not a reuse, with its own key prefix.
 *
 * **Untrusted input.** A stored entry can be hand-edited, truncated by a
 * quota error, or written by an older build. Every read is revalidated
 * field-by-field; anything that doesn't hold is treated as "no draft",
 * never thrown into a render. Same posture `app/dev/devPackageStore.ts`
 * documents for its own `sessionStorage` entries.
 */
import type { AcrossInstances, SeriesBy } from '../metricsRepository';

import type { Resolution } from './ResolutionControl';

const EXPLORE_DRAFT_KEY_PREFIX = 'smc.exploreDraft.';

const SERIES_BY_VALUES: readonly SeriesBy[] = ['instance', 'replica'];
const ACROSS_INSTANCES_VALUES: readonly AcrossInstances[] = ['none', 'sum', 'avg', 'min', 'max'];
const RESOLUTION_VALUES: readonly Resolution[] = ['auto', 'raw'];

export interface ExploreDraftDocument {
  metric: string | null;
  seriesBy: SeriesBy;
  acrossInstances: AcrossInstances;
  groupBy: readonly string[];
  resolution: Resolution;
}

function isExploreDraftDocument(value: unknown): value is ExploreDraftDocument {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return (
    (candidate.metric === null || typeof candidate.metric === 'string') &&
    SERIES_BY_VALUES.includes(candidate.seriesBy as SeriesBy) &&
    ACROSS_INSTANCES_VALUES.includes(candidate.acrossInstances as AcrossInstances) &&
    Array.isArray(candidate.groupBy) &&
    candidate.groupBy.every((key) => typeof key === 'string') &&
    RESOLUTION_VALUES.includes(candidate.resolution as Resolution)
  );
}

function draftStorageKey(id: string): string {
  return `${EXPLORE_DRAFT_KEY_PREFIX}${id}`;
}

/** Short and opaque — long enough to avoid a same-tab collision, short enough to keep the URL readable. */
export function generateExploreDraftId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 12);
  }
  let id = '';
  for (let i = 0; i < 12; i += 1) {
    id += Math.floor(Math.random() * 16).toString(16);
  }
  return id;
}

/** Returns `null` on no draft, or on anything that doesn't revalidate — never throws into a render. */
export function loadExploreDraft(id: string): ExploreDraftDocument | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(draftStorageKey(id));
  } catch {
    // Storage can throw on access alone (disabled cookies, some private
    // modes) — no draft is the correct answer, never a thrown render.
    return null;
  }
  if (raw === null) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  return isExploreDraftDocument(parsed) ? parsed : null;
}

export function saveExploreDraft(id: string, document: ExploreDraftDocument): void {
  try {
    sessionStorage.setItem(draftStorageKey(id), JSON.stringify(document));
  } catch {
    // A quota or security error must not lose the in-memory draft — it
    // still works for this page's lifetime, it just won't survive reload.
  }
}

/** Called when Explore is left unsaved (`DM-D9`) — never on a mere reload, since the URL round-trips the id. */
export function discardExploreDraft(id: string): void {
  try {
    sessionStorage.removeItem(draftStorageKey(id));
  } catch {
    // Nothing to discard if storage itself is unavailable.
  }
}
