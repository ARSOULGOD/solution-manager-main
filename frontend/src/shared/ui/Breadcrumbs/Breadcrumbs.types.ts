export interface BreadcrumbCrumb {
  label: string;
  to?: string;
}

export interface BreadcrumbLabels {
  solution?: string;
  instance?: string;
}

export interface BreadcrumbsProps {
  /** Optional override. When omitted, crumbs are derived from the route. */
  items?: readonly BreadcrumbCrumb[];
  labels?: BreadcrumbLabels;
}

export interface SidebarNavSolution {
  id: string;
  name: string;
  instanceCount: number;
  /**
   * The package's own glyph key (task 033's `SolutionPackageSummary.icon`),
   * resolved through `SolutionGlyph` so the drawer entry and the catalog
   * card carry the same mark. Optional and open: a package naming no
   * glyph — or one this build does not know — falls back to the generic
   * package mark rather than rendering nothing.
   */
  icon?: string | undefined;
}

export interface SidebarNavProps {
  solutions: readonly SidebarNavSolution[];
  /**
   * Rail mode (task 111): icons only, labels hidden, every entry named by
   * a tooltip **and** an `aria-label` so the accessible name survives the
   * label being off screen. `false` — the default — is the full drawer.
   */
  collapsed?: boolean;
  /**
   * Renders the rail's own collapse/expand control when supplied. Omitted
   * where the drawer has no rail state of its own to drive — the overlay
   * drawer below `lg`, and the gallery.
   */
  onToggleCollapsed?: (() => void) | undefined;
  /**
   * Distinct solutions loaded — not `solutions.length`, which counts
   * packages. Omit while the catalog is still loading or failed to load:
   * the count is then rendered as absent rather than as `(0)`, which
   * `UI-D8` forbids (a fabricated zero reads as "none loaded").
   */
  solutionCount?: number | undefined;
}
