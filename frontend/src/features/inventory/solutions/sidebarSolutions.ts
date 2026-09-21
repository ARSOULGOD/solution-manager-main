/**
 * Derives the drawer's solution sub-list from the loaded package catalog
 * (task 036's deferred wiring; UI §A.2, reference `app/shell.jsx`).
 *
 * Two rules, both taken from the reference shell rather than invented:
 *
 * - **The nav counts solutions, not packages.** `SOLUTIONS` holds one row
 *   per *package*, and the same solution can be present more than once
 *   (a signed release plus a nightly build). The reference computes
 *   `new Set(SOLUTIONS.map(s => s.solution)).size`; so does this.
 * - **Dev-mode packages are omitted from the sub-list.** An unsigned
 *   build admitted under `UI-D19` is reachable from `/solutions`, but it
 *   is not a solution the drawer advertises as loaded.
 */
import type { SidebarNavSolution } from '@shared/ui';

import type { SolutionPackageSummary } from './solutionsRepository';

export function toSidebarSolutions(
  packages: readonly SolutionPackageSummary[],
): SidebarNavSolution[] {
  return packages
    .filter((pkg) => pkg.devMode !== true)
    .map((pkg) => ({
      id: pkg.id,
      name: pkg.name,
      instanceCount: pkg.instanceCount,
      // The drawer entry and the catalog card read one glyph key, so a
      // solution looks the same in both places (task 024's icon set).
      icon: pkg.icon,
    }));
}

export function countDistinctSolutions(packages: readonly SolutionPackageSummary[]): number {
  return new Set(packages.map((pkg) => pkg.solution)).size;
}
