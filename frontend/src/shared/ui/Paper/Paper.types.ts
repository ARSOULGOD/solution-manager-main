import type { ReactNode } from 'react';

export type PaperSurface = 'flat' | 'padded' | 'hoverable';

export interface PaperProps {
  children: ReactNode;
  /** Default `padded`. `flat` has no extra padding; `hoverable` raises on hover. */
  surface?: PaperSurface;
  /**
   * Stretch to the full height of the container and lay children out as a
   * column, so a child marked `flex: 1` fills whatever height is left.
   * Default `false`.
   *
   * Added by task 129 for `WidgetFrame`: a dashboard widget occupies a
   * grid cell sized from its own `placement.h`, and a card that sits at
   * its natural height inside that cell leaves visible dead space under
   * the plot. Kept as a named boolean rather than an `sx` escape hatch —
   * this component's contract is that callers choose a named surface, not
   * that they hand-roll padding and layout (see `Paper.tsx`'s header).
   */
  fill?: boolean;
  /**
   * Draw the border dashed instead of solid. Default `false`.
   *
   * Task 129: the reference's `.widget.hidden-def` (theme.css:547) is a
   * dashed card, and `HiddenDefaultWidget`'s own header already claimed to
   * mirror it — but nothing rendered the dash. A border STYLE, not a
   * colour, so `FE-D5`'s block on brand paint does not apply.
   */
  dashed?: boolean;
}
