/**
 * Derives the drawer's solution sub-list from the loaded package catalog
 * (task 036's deferred wiring; UI §A.2, reference `app/shell.jsx`;
 * integration plan 005 Task 2).
 *
 * The nav counts solutions, not packages. `SolutionCatalogItem` holds one
 * row per *package*, and the same solution can be present more than once
 * (two packages sharing one `solution` string); the reference computes
 * `new Set(items.map(s => s.solution)).size`, so does this.
 *
 * CONTRACT: TBD — `instanceCount` is flagged `0`, not invented. `Solution`
 * (`console.yaml`) carries no per-solution instance count; computing a
 * real one needs a join with `listInstances` the contract does not offer
 * at this scope yet. No `icon` either — `Solution` carries no glyph key,
 * and `SidebarNavSolution.icon` is optional, so it is simply omitted.
 */
import type { SidebarNavSolution } from '@shared/ui';

import type { SolutionCatalogItem } from './solutionsRepository';

export function toSidebarSolutions(
  items: readonly SolutionCatalogItem[],
): SidebarNavSolution[] {
  return items.map((item) => ({
    id: item.solutionId,
    name: item.name,
    // CONTRACT: TBD — see file header.
    instanceCount: 0,
  }));
}

export function countDistinctSolutions(items: readonly SolutionCatalogItem[]): number {
  return new Set(items.map((item) => item.solution)).size;
}
