/**
 * DEV-only: wraps the real `SolutionsRepository` so a dropped package is
 * held pending until the operator confirms, and registered in the dev
 * package store from there.
 *
 * **Integration plan 005 note.** `load()` used to receive the raw `File`
 * and read its bytes itself (`parsePackageFile`) because the real
 * repository's `load()` sent only `{ fileName, fileSize }` — see the prior
 * revision of this file. Plan 005 wired `load()` to the real
 * `loadPackage` contract, which takes the full parsed package document;
 * `LoadPackageDialog.tsx` now parses and validates the file itself
 * (`inspectPackageFile.ts`) **before** calling `repository.load()`. This
 * wrapper therefore receives an already-validated document, not a `File`
 * — there is nothing left for it to parse, only to hold pending.
 *
 * | Step | Call | Effect |
 * |---|---|---|
 * | operator picks a file | `load()` | hold the validated document as pending; delegate |
 * | operator confirms | `commitPending()` | pending → registered, catalog shows it |
 * | operator cancels/closes | `discardPending()` | pending dropped, catalog unchanged |
 *
 * Production safety: `SolutionsRoute.tsx` imports this module inside a
 * literal `if (import.meta.env.DEV)` branch, so Vite folds the check to
 * `false` and drops this module graph from a production build (`FE-D24`,
 * the rule `gallery.routes.ts` documents).
 */
import type { CanonicalSolutionPackage } from '@contracts/solution-package';
import type { SolutionsRepository } from '@features/inventory/solutions';

import { devPackageStore } from './devPackageStore';
import type { LoadedPackage } from './parsePackage';

export function withDevPackages(repository: SolutionsRepository): SolutionsRepository {
  return {
    list: (signal) => repository.list(signal),

    getPackage: (solutionId, packageVersion, signal) =>
      repository.getPackage(solutionId, packageVersion, signal),

    async load(pkg: CanonicalSolutionPackage, signal) {
      // `pkg` already passed `inspectPackageFile.ts`'s schema/semantic
      // gates — it is the same shape `parsePackage.ts`'s own gates
      // produce, modulo the never-undeclared `provenance.signature`
      // `LoadedPackage` allows for dev-mode admission.
      devPackageStore.holdPending(pkg as unknown as LoadedPackage);
      return repository.load(pkg, signal);
    },

    commitPending() {
      devPackageStore.commitPending();
    },

    discardPending() {
      devPackageStore.discardPending();
    },
  };
}
