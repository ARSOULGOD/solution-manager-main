/**
 * DEV-only: the packages a developer has dropped on the console this
 * session, keyed by `pkg.solution`.
 *
 * **Why `sessionStorage` and not memory alone.** A dropped package has to
 * survive the reloads the dev server forces on you — an HMR full reload,
 * or simply refreshing the Designer you just opened. It must NOT survive
 * closing the tab: these are dev-mode admissions (`UI-D19`), not
 * onboarded solutions, and a package silently reappearing days later in
 * someone's browser is a worse surprise than re-dropping the file.
 * `sessionStorage` is exactly that lifetime.
 *
 * **Rehydration revalidates.** A storage entry is untrusted input — it can
 * be hand-edited, truncated by a quota error, or written by an older
 * contract. Every entry is re-run through `validatePackage()` and dropped
 * if it no longer holds, and a storage read that throws outright is
 * treated as "no packages". Nothing here may break application startup:
 * this module is loaded during the dev bootstrap, before anything renders.
 *
 * **The pending slot.** Parsing has to happen when the operator PICKS the
 * file — that is the only moment its bytes exist — but a picked file is
 * not a loaded package: the dialog still shows a verification result the
 * operator can walk away from. So `holdPending()` parks the parsed
 * package, `commitPending()` (the dialog's confirm, the same edge that
 * fires `onLoaded`) registers it, and `discardPending()` (cancel/close)
 * throws it away. A package picked but not confirmed never reaches the
 * catalog, and pending state is never persisted.
 *
 * **No top-level side effects.** Rehydration is lazy (first read), not
 * module-init, so importing this module cannot touch `sessionStorage` and
 * a production bundle that somehow retained a reference would still not
 * execute anything. Production never reaches it — `main.tsx` installs the
 * only caller inside a literal `if (import.meta.env.DEV)` branch.
 */
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import type { SolutionPackageSummary } from '@shared/testdoubles/data/solutions';

import { derivePackageCatalog, derivePackageInstance } from './derivePackageCatalog';
import { validatePackage, type LoadedPackage } from './parsePackage';

/** Bumped if the stored shape ever changes; a foreign key is simply ignored. */
export const DEV_PACKAGE_STORAGE_KEY = 'sm.dev.packages.v1';

/** The slice of `Storage` used here — so a test can pass a fake, or nothing at all. */
export interface DevPackageStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface DevPackageStore {
  /** Every package registered this session, in registration order. */
  list(): readonly LoadedPackage[];
  get(solution: string): LoadedPackage | undefined;
  /** Re-registering the same `solution` REPLACES it — dropping an edited package is the point. */
  register(pkg: LoadedPackage): void;
  clear(): void;

  // ---- pending lifecycle (see the module header) ----
  holdPending(pkg: LoadedPackage): void;
  pending(): LoadedPackage | undefined;
  /** Registers the pending package, if there is one. Returns what it registered. */
  commitPending(): LoadedPackage | undefined;
  discardPending(): void;

  // ---- console-side views, derived on every read ----
  /** One catalog card per registered package (`derivePackageCatalog.ts`). */
  summaries(): readonly SolutionPackageSummary[];
  /** One synthesized instance per registered package (`derivePackageInstance()`). */
  instances(): readonly InstanceRow[];
  instanceById(instanceId: string): InstanceRow | undefined;
  /** The package an instance was synthesized from — the Designer route's lookup. */
  packageForInstance(instanceId: string): LoadedPackage | undefined;
}

function readEntries(storage: DevPackageStorage): readonly LoadedPackage[] {
  let raw: string | null;
  try {
    raw = storage.getItem(DEV_PACKAGE_STORAGE_KEY);
  } catch {
    // Storage can throw on access alone (disabled cookies, some private
    // modes). No packages is the correct answer, never a thrown boot.
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

  const packages: LoadedPackage[] = [];
  for (const entry of parsed) {
    // Revalidation, not a cast: see the module header on untrusted entries.
    const result = validatePackage(entry);
    if (result.ok) {
      packages.push(result.pkg);
    }
  }
  return packages;
}

function writeEntries(storage: DevPackageStorage, packages: readonly LoadedPackage[]): void {
  try {
    storage.setItem(DEV_PACKAGE_STORAGE_KEY, JSON.stringify(packages));
  } catch {
    // A quota or security error must not lose the in-memory registration —
    // the package still works for this page's lifetime, it just will not
    // survive a reload.
  }
}

export function createDevPackageStore(storage: DevPackageStorage | undefined): DevPackageStore {
  // `undefined` => memory only. Keeps the store usable in a non-browser
  // context without every call site guarding for it.
  const bySolution = new Map<string, LoadedPackage>();
  let pending: LoadedPackage | undefined;
  let hydrated = false;

  function hydrate(): void {
    if (hydrated) {
      return;
    }
    hydrated = true;
    if (storage === undefined) {
      return;
    }
    for (const pkg of readEntries(storage)) {
      bySolution.set(pkg.solution, pkg);
    }
  }

  function persist(): void {
    if (storage !== undefined) {
      writeEntries(storage, [...bySolution.values()]);
    }
  }

  function list(): readonly LoadedPackage[] {
    hydrate();
    return [...bySolution.values()];
  }

  function register(pkg: LoadedPackage): void {
    hydrate();
    bySolution.set(pkg.solution, pkg);
    persist();
  }

  return {
    list,
    get(solution) {
      hydrate();
      return bySolution.get(solution);
    },
    register,
    clear() {
      hydrate();
      bySolution.clear();
      pending = undefined;
      persist();
    },

    holdPending(pkg) {
      pending = pkg;
    },
    pending() {
      return pending;
    },
    commitPending() {
      if (pending === undefined) {
        return undefined;
      }
      const pkg = pending;
      pending = undefined;
      register(pkg);
      return pkg;
    },
    discardPending() {
      pending = undefined;
    },

    summaries() {
      return list().map(derivePackageCatalog);
    },
    instances() {
      return list().map(derivePackageInstance);
    },
    instanceById(instanceId) {
      return list()
        .map(derivePackageInstance)
        .find((instance) => instance.id === instanceId);
    },
    packageForInstance(instanceId) {
      return list().find((pkg) => derivePackageInstance(pkg).id === instanceId);
    },
  };
}

function sessionStorageOrUndefined(): DevPackageStorage | undefined {
  try {
    return globalThis.sessionStorage;
  } catch {
    return undefined;
  }
}

/** The one store the dev flow shares — MSW handlers and the repository decorator both read it. */
export const devPackageStore: DevPackageStore = createDevPackageStore(sessionStorageOrUndefined());
