/**
 * DEV-only: the instances an operator has declared in the console this
 * session, across every solution.
 *
 * **Why this is not part of `devPackageStore`.** That store holds
 * PACKAGES and derives exactly one instance per package, mechanically. An
 * operator-declared instance has the opposite provenance — authored, not
 * derived — and a different lifecycle: several may exist per solution,
 * one can be declared against a baked-in fixture solution that has no
 * loaded package at all, and re-dropping a package must not silently
 * replace them. Folding them into a package-keyed map would mean either
 * losing declarations on a re-drop or making that map no longer
 * package-keyed. So: a second store, same discipline.
 *
 * **Same storage posture as `devPackageStore.ts`, for the same reasons.**
 * `sessionStorage`, so a declaration survives the reloads a dev server
 * forces on you but not closing the tab; every entry revalidated on
 * rehydration, because a storage entry is untrusted input; no top-level
 * side effects, because this module loads during the dev bootstrap,
 * before anything renders.
 *
 * **What is stored is the DECLARATION, not the row.** The row is rebuilt
 * from it on every read, exactly as `devPackageStore` rebuilds its
 * instance from the package. Storing the row instead would persist the
 * derived observable facts alongside the declared ones, and a hand-edited
 * `"healthy"` would then survive a reload — which is the one thing the
 * whole not-yet-observed posture exists to prevent.
 *
 * The ROW SHAPE is not decided here — `@shared/testdoubles/data/declaredInstance`
 * owns it, so the default fixture handlers and this store cannot disagree
 * about what a just-declared instance looks like.
 *
 * **Removals are TOMBSTONES, not deletions** (task 117). An instance can
 * be undeclared whether it came from this store or from the baked fixture
 * set, and a fixture array cannot be mutated — so a removal that does not
 * match a declaration is recorded as a removed id, and the handlers filter
 * the merged list through it. Kept in its own storage key rather than
 * folded into the declaration entries: the two answer different questions
 * ("what did the operator add" vs "what did they take away"), and a
 * combined shape would have forced a migration of the working v1 format.
 *
 * DEV-only: reached exclusively from `devPackageHandlers.ts`, which
 * `main.tsx` installs inside a literal `if (import.meta.env.DEV)` branch.
 */
import type { DeclareInstanceInput } from '@shared/api/contracts/instances';
import { buildDeclaredInstance, readDeclareInput } from '@shared/testdoubles/data/declaredInstance';
import type { InstanceRow } from '@shared/testdoubles/data/instances';

/** Bumped if the stored shape ever changes; a foreign key is simply ignored. */
export const DEV_DECLARED_STORAGE_KEY = 'sm.dev.declaredInstances.v1';

/** Ids undeclared this session — see the header on why this is separate. */
export const DEV_REMOVED_STORAGE_KEY = 'sm.dev.removedInstances.v1';

/** The slice of `Storage` used here — so a test can pass a fake, or nothing at all. */
export interface DevDeclaredStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type DeclareRejectionCode = 'invalid' | 'duplicate';

export interface DeclareRejection {
  code: DeclareRejectionCode;
  /** One sentence, safe to show an operator. */
  message: string;
}

export type DeclareOutcome =
  { ok: true; instance: InstanceRow } | { ok: false; rejection: DeclareRejection };

export interface DevDeclaredInstanceStore {
  /** Every declaration this session, in declaration order. */
  list(): readonly InstanceRow[];
  forSolution(solutionId: string): readonly InstanceRow[];
  byId(instanceId: string): InstanceRow | undefined;
  /**
   * Builds and registers the row, or states why it could not. `taken` is
   * every name the merged grid would show — a declaration must not collide
   * with a FIXTURE row either, since the routes key on the id and two rows
   * sharing one is ambiguous.
   *
   * `liveConnectionRevisions` is the CURRENT catalog's `id -> revision` map
   * at the moment this declare is accepted (`UI-D27`/`UI-D29`) — the caller
   * (the handler, which can see the live connections store) supplies it.
   * Only entries whose id the declaration actually selected are kept; the
   * result is frozen into the stored declaration and never recomputed.
   */
  declare(
    solutionId: string,
    input: unknown,
    taken: readonly string[],
    liveConnectionRevisions?: Readonly<Record<string, number>>,
  ): DeclareOutcome;
  /**
   * Removes SM's record of `instanceId`. Drops a declaration made this
   * session, or tombstones a fixture id. Returns false when the id is
   * already gone, so the handler can answer 404 rather than pretend.
   */
  undeclare(instanceId: string): boolean;
  /** Ids the merged instance list must exclude. */
  removedIds(): ReadonlySet<string>;
  clear(): void;
}

const INVALID_MESSAGE =
  'The declaration is missing a valid name, package version, org, sub-org, runtime, locus or replica list.';

/** What is persisted: the declaration itself, flat, plus the solution it names. */
type StoredDeclaration = DeclareInstanceInput & {
  solutionId: string;
  /**
   * The FROZEN revision of each selected connection, computed once by
   * `declare()` and never recomputed on read — see `buildDeclaredInstance`'s
   * own doc on why (`UI-D29`). Absent when the declaration selects no
   * connection.
   */
  appliedConnectionRevisions?: Readonly<Record<string, number>>;
};

/** `Record<string, number>`, structurally — the shape `appliedConnectionRevisions` needs. */
function isRevisionRecord(value: unknown): value is Record<string, number> {
  return (
    typeof value === 'object' &&
    value !== null &&
    Object.values(value as Record<string, unknown>).every((entry) => typeof entry === 'number')
  );
}

function readStored(value: unknown): StoredDeclaration | undefined {
  const solutionId: unknown =
    typeof value === 'object' && value !== null
      ? (value as Record<string, unknown>)['solutionId']
      : undefined;
  if (typeof solutionId !== 'string' || solutionId === '') {
    return undefined;
  }
  // Revalidation, not a cast — see the module header on untrusted entries.
  const input = readDeclareInput(value);
  if (input === undefined) {
    return undefined;
  }
  const rawRevisions = (value as Record<string, unknown>)['appliedConnectionRevisions'];
  return {
    ...input,
    solutionId,
    ...(isRevisionRecord(rawRevisions) ? { appliedConnectionRevisions: rawRevisions } : {}),
  };
}

function readEntries(storage: DevDeclaredStorage): readonly StoredDeclaration[] {
  let raw: string | null;
  try {
    raw = storage.getItem(DEV_DECLARED_STORAGE_KEY);
  } catch {
    // Storage can throw on access alone (disabled cookies, some private
    // modes). No declarations is the correct answer, never a thrown boot.
    return [];
  }
  if (raw === null) {
    return [];
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) {
    return [];
  }

  const entries: StoredDeclaration[] = [];
  for (const entry of parsed) {
    const stored = readStored(entry);
    if (stored !== undefined) {
      entries.push(stored);
    }
  }
  return entries;
}

function writeEntries(storage: DevDeclaredStorage, entries: readonly StoredDeclaration[]): void {
  try {
    storage.setItem(DEV_DECLARED_STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // A quota or security error must not lose the in-memory declaration —
    // it still works for this page's lifetime, it just will not survive a
    // reload.
  }
}

function readRemoved(storage: DevDeclaredStorage): readonly string[] {
  let raw: string | null;
  try {
    raw = storage.getItem(DEV_REMOVED_STORAGE_KEY);
  } catch {
    return [];
  }
  if (raw === null) {
    return [];
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [];
}

function writeRemoved(storage: DevDeclaredStorage, ids: readonly string[]): void {
  try {
    storage.setItem(DEV_REMOVED_STORAGE_KEY, JSON.stringify(ids));
  } catch {
    // Same posture as `writeEntries` — an unwritable store loses the
    // removal on reload, never the in-memory one.
  }
}

function toRow(entry: StoredDeclaration): InstanceRow {
  // `appliedConnectionRevisions` degraded 2026-10-09 (plan 007): the real
  // `DeclareInstanceInput` carries no `connections` to snapshot a revision
  // for — see this module's `declare()` and the class header note.
  const { appliedConnectionRevisions, ...input } = entry;
  void appliedConnectionRevisions;
  return buildDeclaredInstance(input);
}

export function createDevDeclaredInstanceStore(
  storage: DevDeclaredStorage | undefined,
): DevDeclaredInstanceStore {
  // `undefined` => memory only. Keeps the store usable in a non-browser
  // context without every call site guarding for it.
  const byName = new Map<string, StoredDeclaration>();
  const removed = new Set<string>();
  let hydrated = false;

  function hydrate(): void {
    if (hydrated) {
      return;
    }
    hydrated = true;
    if (storage === undefined) {
      return;
    }
    for (const entry of readEntries(storage)) {
      byName.set(entry.name.toLowerCase(), entry);
    }
    for (const id of readRemoved(storage)) {
      removed.add(id);
    }
  }

  function persist(): void {
    if (storage !== undefined) {
      writeEntries(storage, [...byName.values()]);
      writeRemoved(storage, [...removed]);
    }
  }

  function entries(): readonly StoredDeclaration[] {
    hydrate();
    return [...byName.values()];
  }

  function list(): readonly InstanceRow[] {
    return entries().map(toRow);
  }

  return {
    list,
    forSolution(solutionId) {
      return entries()
        .filter((entry) => entry.solutionId === solutionId)
        .map(toRow);
    },
    byId(instanceId) {
      const entry = entries().find((candidate) => candidate.name === instanceId);
      return entry === undefined ? undefined : toRow(entry);
    },
    declare(solutionId, input, taken, liveConnectionRevisions) {
      hydrate();
      const parsed = readDeclareInput(input);
      if (parsed === undefined) {
        return { ok: false, rejection: { code: 'invalid', message: INVALID_MESSAGE } };
      }

      const key = parsed.name.toLowerCase();
      const clash = byName.has(key) || taken.some((name) => name.toLowerCase() === key);
      if (clash) {
        return {
          ok: false,
          rejection: {
            code: 'duplicate',
            message: `An instance named “${parsed.name}” is already declared.`,
          },
        };
      }

      // `connections` degraded 2026-10-09 (plan 007): the real
      // `DeclareInstanceInput` has no connection selectors to snapshot a
      // revision for — `liveConnectionRevisions` is accepted but unused.
      void liveConnectionRevisions;
      const entry: StoredDeclaration = { ...parsed, solutionId };
      byName.set(key, entry);
      // Re-declaring a name that was undeclared lifts its tombstone —
      // otherwise the merged list would filter the row straight back out
      // and the declaration would appear to have silently failed.
      removed.delete(parsed.name);
      persist();
      return { ok: true, instance: toRow(entry) };
    },
    undeclare(instanceId) {
      hydrate();
      const key = instanceId.toLowerCase();
      const declared = byName.get(key);
      if (declared !== undefined) {
        byName.delete(key);
        // Tombstoned as well as dropped: a declaration made this session
        // may share a name with a fixture row in a future fixture set, and
        // "removed" is the operator's intent either way.
        removed.add(declared.name);
        persist();
        return true;
      }
      if (removed.has(instanceId)) {
        return false;
      }
      removed.add(instanceId);
      persist();
      return true;
    },
    removedIds() {
      hydrate();
      return new Set(removed);
    },
    clear() {
      hydrate();
      byName.clear();
      removed.clear();
      persist();
    },
  };
}

function sessionStorageOrUndefined(): DevDeclaredStorage | undefined {
  try {
    return globalThis.sessionStorage;
  } catch {
    return undefined;
  }
}

/**
 * The one store the dev declare flow shares. Storage is only READ lazily,
 * on first use — see the header on no top-level side effects.
 */
export const devDeclaredInstances: DevDeclaredInstanceStore = createDevDeclaredInstanceStore(
  sessionStorageOrUndefined(),
);
