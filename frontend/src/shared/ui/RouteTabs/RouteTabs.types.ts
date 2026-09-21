import type { ReactElement, ReactNode } from 'react';

export interface RouteTab {
  to: string;
  label: string;
  icon?: ReactElement;
  count?: number;
  /**
   * Match `to` exactly, never as a path prefix — the same meaning
   * react-router gives `NavLink`'s own `end` prop. Default `false`, so
   * every existing tab keeps prefix matching and a nested route still
   * activates its parent tab.
   *
   * Needed (task 130) whenever one tab's path is a prefix of a sibling's:
   * Monitoring's `/monitoring` and `/monitoring/explore` would otherwise
   * both match on the child route, and the parent would win.
   */
  end?: boolean;
}

export interface RouteTabsProps {
  tabs: readonly RouteTab[];
  children: ReactNode;
  /**
   * Optional content at the end of the tab row — an `InfoTip` explaining
   * the section, say (task 131).
   *
   * Rendered as a SIBLING of the `role="tablist"`, never inside it: a
   * tablist may only contain tabs, and anything else there fails axe's
   * `aria-required-children`.
   */
  trailing?: ReactNode;
}
