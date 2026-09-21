/**
 * DEV-only: wraps the real `SolutionsRepository` so the file the operator
 * drops is actually read.
 *
 * **The wire contract is untouched.** `load()` still calls through to the
 * real repository, which still posts `{ fileName, fileSize }` and nothing
 * else — the package BYTES are read here, in the browser, and never enter
 * the repository or the API. `solutionsRepository.ts`'s header explains
 * why the bytes have nowhere to go: the upload wire format is `§16 Q10`
 * TBD, and inventing one would assert a contract nobody ratified.
 *
 * What the wrapper adds is the parse and the pending lifecycle:
 *
 * | Step | Call | Effect |
 * |---|---|---|
 * | operator picks a file | `load()` | parse; hold as pending; delegate |
 * | operator confirms | `commitPending()` | pending → registered, catalog shows it |
 * | operator cancels/closes | `discardPending()` | pending dropped, catalog unchanged |
 *
 * A parse failure is surfaced as a rejected `load()` — an `ApiError`
 * carrying the structured reason — so the dialog's existing inline error
 * renders it and the Load button stays disabled. That path invents no new
 * UI and, importantly, does not dress a rejected file up as an unsigned
 * one: "this is not a package" and "this package is not signed" are
 * different answers and read differently on screen.
 *
 * Production safety: `SolutionsRoute.tsx` imports this module inside a
 * literal `if (import.meta.env.DEV)` branch, so Vite folds the check to
 * `false` and drops this module graph from a production build (`FE-D24`,
 * the rule `gallery.routes.ts` documents).
 */
import type { SolutionsRepository } from '@features/inventory/solutions';
import { ApiError } from '@shared/api';

import { devPackageStore } from './devPackageStore';
import { parsePackageFile, type PackageRejection } from './parsePackage';

/** 422 — the request was well-formed, the file it named was not processable. */
const UNPROCESSABLE = 422;

function toApiError(rejection: PackageRejection): ApiError {
  return new ApiError({
    status: UNPROCESSABLE,
    code: rejection.code,
    message:
      rejection.detail === undefined
        ? rejection.message
        : `${rejection.message} — ${rejection.detail}`,
  });
}

export function withDevPackages(repository: SolutionsRepository): SolutionsRepository {
  return {
    list: (signal) => repository.list(signal),

    async load(file, signal) {
      // `SolutionsRepository.load()` accepts `Pick<File, 'name' | 'size'>`
      // — a unit test may pass exactly that. Only a real `File` has bytes
      // to read, and anything else delegates untouched rather than being
      // rejected for a shape the interface explicitly allows.
      if (!(file instanceof File)) {
        return repository.load(file, signal);
      }

      const parsed = await parsePackageFile(file);
      if (!parsed.ok) {
        throw toApiError(parsed.rejection);
      }

      devPackageStore.holdPending(parsed.pkg);
      return repository.load(file, signal);
    },

    commitPending() {
      devPackageStore.commitPending();
    },

    discardPending() {
      devPackageStore.discardPending();
    },
  };
}
