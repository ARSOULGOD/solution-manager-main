import type { ViewSection } from '@contracts/view-model/types';

export interface SectionNavProps {
  /**
   * `ViewModel.sections`, verbatim and in order. `ViewSection` carries no
   * component-instance identity (`FE-046`'s contract stops at `title` /
   * `order` / `fields` / `actions`), so this rail cannot group entries
   * under a component header the way UI §A.8.7 describes for a
   * multi-component solution — see `SectionNav.tsx`'s module doc. It
   * renders correctly as a flat list for any single-component solution
   * (Cache, the v1 target).
   */
  sections: readonly ViewSection[];
  /** Index into `sections` — the same index a caller passes to `SectionCard` for the matching entry. */
  onJump: (index: number) => void;
}
