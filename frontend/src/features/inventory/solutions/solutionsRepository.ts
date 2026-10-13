/**
 * `SolutionsRepository` (task 033/040, UI §A.5, §A.5 load-package dialog;
 * integration plan 005 Task 2). Wired to `console.yaml`'s `listSolutions`
 * (`GET /cp/solutions` → `SolutionList`) and `loadPackage`
 * (`POST /cp/solutions/load` → `LoadPackageResult`).
 *
 * `SolutionCatalogItem` carries **only** the fields `Solution` declares —
 * `solutionId`, `solution`, `name`, `packageVersions`, `latestPackageVersion`,
 * `signature`, `stateVersion`. The pre-005 fixture shape (`color`, `icon`,
 * `kind`, `entitled`, `instanceCount`, `telemetryMode`, `components`,
 * `authoring`, `appliesTo`, `provenance`, `devMode`, `published`) is NOT
 * carried over: none of it exists on `Solution`, and plan 002's prohibition
 * on synthesizing such fields (restated by plan 005 Task 2) still holds.
 * See `docs/plans/integration/reports/005-task-report.md` for the open
 * decision this leaves for product/design — the catalog card loses the
 * provenance/review/entitlement UI that depended on those fields, and the
 * drawer's per-solution instance count is a flagged `0` (see
 * `sidebarSolutions.ts`), not an invented number.
 */
import { toFrontendPackage } from '@contracts/solution-package';
import type { CanonicalSolutionPackage } from '@contracts/solution-package';
import type { SolutionPackage } from '@contracts/view-model/types';
import type { PlaneClient } from '@shared/api';

/** Mirrors `console.yaml`'s `PackageVersionId` (non-empty string, or an integer ≥ 1). */
export type PackageVersionId = string | number;

/** The catalog row exactly as `Solution` (console.yaml) declares it — nothing added. */
export interface SolutionCatalogItem {
  solutionId: string;
  solution: string;
  name: string;
  packageVersions: readonly PackageVersionId[];
  latestPackageVersion: PackageVersionId;
  /** v1 loads no signed packages (`console.yaml` `Solution.signature`) — always `unsigned`. */
  signature: 'unsigned';
  stateVersion: number;
}

/**
 * `LoadPackageResult` exactly as `console.yaml` declares it, plus the
 * server's correlation id (`FE-D13`) for the caller's own success toast.
 * No `verified`/`devMode`/`signatureFingerprint`/`message` — the fixture
 * era invented a server-side signature-verification step `loadPackage`
 * does not have; see the file header.
 */
export interface PackageLoadResult {
  solutionId: string;
  packageVersion: readonly PackageVersionId[];
  signature: 'unsigned';
  stateVersion: number;
  /** The server's correlation id (`FE-D13`) — carried through for the caller's own success toast. */
  correlationId: string;
}

interface SolutionListEnvelope {
  items: readonly SolutionCatalogItem[];
}

/** console.yaml's `SolutionPackageDocument` (`getSolutionPackage`, D-4) — `Versioned` + the raw document. */
interface SolutionPackageDocumentEnvelope {
  stateVersion: number;
  solutionId: string;
  packageVersion: PackageVersionId;
  package: SolutionPackage;
}

/**
 * `packageVersion` as the canonical JSON text `getSolutionPackage`'s query param expects
 * (`PackageVersionId`'s two shapes: `7`, `"v2"`) — never the bare, unquoted string a string
 * version would otherwise become in a URL.
 */
function canonicalPackageVersionText(packageVersion: string | number): string {
  return typeof packageVersion === 'number' ? String(packageVersion) : JSON.stringify(packageVersion);
}

export interface SolutionsRepository {
  list(signal?: AbortSignal): Promise<SolutionCatalogItem[]>;
  /**
   * `packageVersion` omitted fetches the entry's `latestPackageVersion` (console.yaml's own
   * `getSolutionPackage` rule) — added for plan 008 Task 1's `ConfigRepository.loadDesigner`,
   * which may load a `Designer` envelope whose `viewModel.packageVersion` is `null` (no
   * generation exists yet). The returned document is normalized to the frontend package
   * view-model shape so declare-time role discovery and the Designer use the same metadata.
   */
  getPackage(
    solutionId: string,
    packageVersion?: string | number,
    signal?: AbortSignal,
  ): Promise<SolutionPackage>;
  /**
   * Loads a validated Solution Package document (`loadPackage`,
   * `POST /cp/solutions/load`). The body is `{ package: <document> }` —
   * the full canonical document the browser already parsed and validated
   * (`inspectPackageFile.ts`), never file metadata alone.
   */
  load(pkg: CanonicalSolutionPackage, signal?: AbortSignal): Promise<PackageLoadResult>;
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
  commitPending?(): void;
  discardPending?(): void;
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

export function createSolutionsRepository(cpClient: PlaneClient): SolutionsRepository {
  return {
    async list(signal) {
      const envelope = await cpClient.get<SolutionListEnvelope>('/solutions', opts(signal));
      return [...envelope.items];
    },
    async getPackage(solutionId, packageVersion, signal) {
      const query =
        packageVersion === undefined
          ? ''
          : `?packageVersion=${encodeURIComponent(canonicalPackageVersionText(packageVersion))}`;
      const envelope = await cpClient.get<SolutionPackageDocumentEnvelope>(
        `/solutions/${encodeURIComponent(solutionId)}/package${query}`,
        opts(signal),
      );
      // The API returns the canonical package document.  The Designer consumes
      // the normalized view-model shape (including optionsSource, valueFrom,
      // labels, helpers, and advanced metadata), just like the upload/dev path.
      // Keep this conversion at the repository boundary so every real-API
      // caller sees the same package shape as the static/dev caller.
      return toFrontendPackage(envelope.package as unknown as CanonicalSolutionPackage);
    },
    async load(pkg, signal) {
      const { data, correlationId } = await cpClient.postWithCorrelation<Omit<PackageLoadResult, 'correlationId'>>(
        '/solutions/load',
        { package: pkg },
        opts(signal),
      );
      return { ...data, correlationId };
    },
  };
}
