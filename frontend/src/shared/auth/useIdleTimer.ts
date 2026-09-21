/**
 * Idle detection AND the idle-lock draft-preservation mechanism (task 031,
 * proposed `FE-D25`, plan §16.1). Both live here because they are two
 * halves of one feature: `useIdleTimer` decides WHEN a lock happens;
 * `saveDraft`/`loadDraft`/`useDraftAutosave`/`useDraftRestore` decide what
 * survives it.
 *
 * **Scope, exactly as `FE-D25` decided it — never widen this silently:**
 * - Designer drafts ONLY. Never metrics, Explore, or anything else.
 * - `sessionStorage` (per-tab, gone when the tab closes) — never
 *   `localStorage` (`FE-D14`).
 * - Keyed `smc.draft.<instanceId>`.
 * - A pending secret value NEVER touches storage (`G7`, `I6`) — callers
 *   pass the keys that are secret-typed via `secretKeys`; this module
 *   strips them even if they are still present in `values`, as a second
 *   line of defence on top of the caller's own filtering.
 * - Restoration is never silent — `useDraftRestore` hands back the record
 *   so the consuming screen (Designer, task 046+) renders an explicit
 *   banner with a Discard action. This module does not render UI itself —
 *   Designer doesn't exist yet in Phase 2 — but ships the full mechanism.
 */
import { useEffect, useState } from 'react';

const DRAFT_KEY_PREFIX = 'smc.draft.';
const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart'] as const;
const DEFAULT_AUTOSAVE_DEBOUNCE_MS = 800;

// ---- Idle detection ----------------------------------------------------

/**
 * Calls `onIdle` after `timeoutMinutes` of no mouse/keyboard/scroll/touch
 * activity. Pass `timeoutMinutes <= 0` (e.g. while unauthenticated) to
 * disable the timer entirely.
 */
export function useIdleTimer(timeoutMinutes: number, onIdle: () => void): void {
  useEffect(() => {
    if (timeoutMinutes <= 0) {
      return undefined;
    }

    let timer: ReturnType<typeof setTimeout>;
    const reset = () => {
      clearTimeout(timer);
      timer = setTimeout(onIdle, timeoutMinutes * 60_000);
    };

    reset();
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, reset, { passive: true });
    }

    return () => {
      clearTimeout(timer);
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, reset);
      }
    };
  }, [timeoutMinutes, onIdle]);
}

// ---- Draft preservation -------------------------------------------------

export type DraftFieldValue = string | number | boolean | null;
export type DraftValues = Readonly<Record<string, DraftFieldValue>>;

export interface DraftRecord {
  instanceId: string;
  savedAt: string;
  values: DraftValues;
}

function draftStorageKey(instanceId: string): string {
  return `${DRAFT_KEY_PREFIX}${instanceId}`;
}

/** Writes a draft, stripping every key named in `secretKeys` first (`G7`). */
export function saveDraft(
  instanceId: string,
  values: DraftValues,
  secretKeys: readonly string[] = [],
): void {
  const secretKeySet = new Set(secretKeys);
  const redacted: Record<string, DraftFieldValue> = {};
  for (const [key, value] of Object.entries(values)) {
    if (secretKeySet.has(key)) {
      continue;
    }
    redacted[key] = value;
  }
  const record: DraftRecord = { instanceId, savedAt: new Date().toISOString(), values: redacted };
  sessionStorage.setItem(draftStorageKey(instanceId), JSON.stringify(record));
}

/** Returns `null` on no draft, or on a corrupt/unparseable one — never throws. */
export function loadDraft(instanceId: string): DraftRecord | null {
  const raw = sessionStorage.getItem(draftStorageKey(instanceId));
  if (raw === null) {
    return null;
  }
  try {
    return JSON.parse(raw) as DraftRecord;
  } catch {
    return null;
  }
}

export function clearDraft(instanceId: string): void {
  sessionStorage.removeItem(draftStorageKey(instanceId));
}

export function hasDraft(instanceId: string): boolean {
  return sessionStorage.getItem(draftStorageKey(instanceId)) !== null;
}

/**
 * Debounced autosave. Cleared on successful save, on apply, and on
 * explicit discard are the CALLER's responsibility (`clearDraft`) — this
 * hook only ever writes, matching FE-D25's "written on a debounced change".
 */
export function useDraftAutosave(
  instanceId: string,
  values: DraftValues,
  secretKeys: readonly string[] = [],
  debounceMs = DEFAULT_AUTOSAVE_DEBOUNCE_MS,
): void {
  useEffect(() => {
    const timer = setTimeout(() => saveDraft(instanceId, values, secretKeys), debounceMs);
    return () => clearTimeout(timer);
  }, [instanceId, values, secretKeys, debounceMs]);
}

/**
 * Reads a draft once on mount (sessionStorage is synchronous — no loading
 * state needed) and exposes `discard()` to clear it. The consuming screen
 * decides how to render the "restored" banner and its Discard action;
 * this hook only ever hands back data, never JSX.
 */
export function useDraftRestore(instanceId: string): {
  draft: DraftRecord | null;
  discard: () => void;
} {
  const [draft, setDraft] = useState<DraftRecord | null>(() => loadDraft(instanceId));

  const discard = () => {
    clearDraft(instanceId);
    setDraft(null);
  };

  return { draft, discard };
}
