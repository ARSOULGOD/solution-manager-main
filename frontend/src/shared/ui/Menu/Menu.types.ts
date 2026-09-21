import type { MouseEventHandler, ReactNode } from 'react';

export interface MenuItemDef {
  kind?: 'item';
  label: string;
  /**
   * Leading icon (UI §A.1 `.mi` — the reference draws every action row
   * `icon + label`, never a bare string). Decorative: the row is named by
   * its `label`, so an icon passed here must be `aria-hidden`, which every
   * `SvgIcon` in this repo already is.
   */
  icon?: ReactNode;
  /** Trailing adornment pinned to the row's end — a `preview` tag, a shortcut hint. */
  trailing?: ReactNode;
  onClick?: MouseEventHandler<HTMLElement>;
  danger?: boolean;
  disabled?: boolean;
  /** Renders the row in its selected state (`.mi.on`). */
  selected?: boolean;
}

export interface MenuCaptionDef {
  kind: 'caption';
  label: string;
}

/** Free-form content row — a panel (e.g. identity block, support info) rather than a clickable item. */
export interface MenuContentDef {
  kind: 'content';
  content: ReactNode;
}

/** A rule between groups of rows (`hr.divider` in the reference popovers). */
export interface MenuDividerDef {
  kind: 'divider';
}

export type MenuEntry = MenuItemDef | MenuCaptionDef | MenuContentDef | MenuDividerDef;

export interface MenuProps {
  trigger: ReactNode;
  entries: readonly MenuEntry[];
  /**
   * Popover width floor, matching the reference's per-menu `width`
   * (default 230px). A menu wider than this grows to its content rather
   * than clipping it — see `Menu.tsx`'s note on the collapsed app bar.
   */
  width?: number;
}
