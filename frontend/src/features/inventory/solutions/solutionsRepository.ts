/**
 * `SolutionsRepository` (task 033/040, UI §A.5, §A.5 load-package dialog).
 * CONTRACT: TBD — `§16 Q10` flags the whole packages endpoint unspecified,
 * `load()` included. `SolutionPackageSummary` is defined in the fixture
 * module (task 033) because that is the only place its shape is stated
 * today; a real contract replaces both.
 */
import type { PlaneClient } from '@shared/api';
import type { SolutionPackageSummary } from '@shared/testdoubles/data/solutions';

export type { SolutionPackageSummary };

/**
 * The signature-verification result (task 040). INVENTED shape — the real
 * upload endpoint, its body, and whether verification is a step distinct
 * from loading are all `§16 Q10` TBD. `devMode: true` means the package
 * carries no Solace signature; whether that is enough to ADMIT it is the
 * caller's decision (`UI-D19`: only in an explicit development mode).
 */
export interface PackageLoadResult {
  verified: boolean;
  devMode: boolean;
  signatureFingerprint?: string;
  message: string;
  /** The server's correlation id (`FE-D13`) — carried through for the caller's own success toast. */
  correlationId: string;
}

export type PackageUpload = File | Pick<File, 'name' | 'size'>;

export interface SolutionsRepository {
  list(signal?: AbortSignal): Promise<SolutionPackageSummary[]>;
  /**
   * Submits a selected package file for signature verification and load.
   * Sends file METADATA only (`name`/`size`), never the bytes — the real
   * upload wire format (multipart? chunked?) is unspecified (§16 Q10), and
   * inventing one here would assert a contract nobody has ratified.
   */
  load(file: PackageUpload, signal?: AbortSignal): Promise<PackageLoadResult>;
  /**
   * The load dialog's confirm/dismiss edges, for an implementation that
   * holds per-selection state between `load()` and the operator's answer.
   *
   * BOTH ARE OPTIONAL AND BOTH ARE ABSENT HERE. The real repository holds
   * nothing between calls — a `POST /solutions/load` is the whole
   * interaction — so there is nothing for it to commit or discard, and
   * declaring empty methods would imply otherwise. The dev package loader
   * (`app/dev/devSolutionsRepository.ts`, DEV-only) does hold state: it
   * parses the dropped file's bytes locally and must not put the result in
   * the catalog until the operator confirms. The dialog calls these
   * through `?.()`, so a repository that does not implement them is not a
   * special case anywhere.
   */
  commitPending?(): Promise<void> | void;
  discardPending?(): Promise<void> | void;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createSolutionsRepository(cpClient: PlaneClient): SolutionsRepository {
  let pending = false;
  return {
    list(signal) {
      return cpClient.get<SolutionPackageSummary[]>('/solutions', opts(signal));
    },
    async load(file, signal) {
      if (typeof File === 'undefined' || !(file instanceof File)) {
        throw new Error('The live package repository requires the selected package file.');
      }
      const packageJson = await file.text();
      const { data, correlationId } = await cpClient.postWithCorrelation<Omit<PackageLoadResult, 'correlationId'>>(
        '/solutions/load', { packageJson }, opts(signal));
      pending = true;
      return { ...data, correlationId };
    },
    async commitPending() {
      if (!pending) return;
      await cpClient.post('/solutions/load/confirm');
      pending = false;
    },
    async discardPending() {
      if (!pending) return;
      await cpClient.post('/solutions/load/discard');
      pending = false;
    },
  };
}
