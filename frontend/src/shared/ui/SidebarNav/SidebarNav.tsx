/**
 * Navy drawer nav (FE-024, FE-111, FE-112, UI §A.2). v1 contains only
 * Home and Solutions plus a sub-list of loaded solutions. `Targets` and
 * `Admin` are omitted until those routes ship (design §14). Active item:
 * `aria-current="page"` **and** a non-colour accent bar (never colour
 * alone).
 *
 * **Icons (task 024).** Every entry carries a glyph. The two platform
 * entries use this folder's own `icons.tsx`; a *solution* entry uses
 * `SolutionGlyph` keyed by the package's `icon` field, so the drawer and
 * the catalog card (task 039) show one solution's identity through one
 * mark rather than two drifting ones. Unknown or absent glyph keys fall
 * back to the generic package mark — the drawer never renders a hole.
 *
 * **Rail mode (`collapsed`, task 111).** Task 111 first shipped desktop
 * collapse as "full close": the whole drawer unmounted, so a collapsed
 * shell had no navigation on screen at all and the only way back was the
 * app bar's hamburger. Collapsing to a rail keeps every destination
 * reachable in one click at a fraction of the width. Labels leave the
 * layout, never the accessibility tree — each entry keeps an
 * `aria-label` (its full expanded label, instance count included) and
 * gains a `Tooltip`, so a screen-reader user and a sighted user get the
 * same information.
 *
 * `MuiTooltip` is used directly rather than `shared/ui`'s `Tooltip`
 * wrapper: that wrapper exists to make *unfocusable* triggers reachable
 * by wrapping them in a `tabIndex={0}` `role="button"` span (task 014).
 * A nav link is already focusable, and nesting it inside a `role="button"`
 * span would both misreport the role and put a second stop in the tab
 * order.
 *
 * **Active state.** Computed with `useMatch` against the same `to`/`end`
 * pair the `NavLink` navigates with, so the two can never disagree — the
 * link owns `aria-current` and its `.active` class, and the match drives
 * the styling and the status dot. Every solution sub-route lives under
 * `/solutions/:solutionId/instances` (`app/routes.ts`), so a
 * non-`end` match on that path is active for Designer, Governance and
 * Monitoring too.
 */
import {
  Box,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Tooltip as MuiTooltip,
  Typography,
} from '@mui/material';
import { alpha } from '@mui/material/styles';
import type { ReactNode } from 'react';
import { NavLink, useMatch } from 'react-router';

import { t } from '@shared/i18n';

import type { SidebarNavProps } from '../Breadcrumbs/Breadcrumbs.types';
import { SolutionGlyph } from '../internal/solutionGlyphs';

import { ChevronLeftIcon, ChevronRightIcon, GridIcon, HomeIcon } from './icons';

export function SidebarNav({
  solutions,
  solutionCount,
  collapsed = false,
  onToggleCollapsed,
}: SidebarNavProps) {
  // No count until one is known. `solutions.length` is the wrong fallback
  // (it counts packages, and is 0 while the catalog is still in flight),
  // and rendering "(0)" from an unknown value is exactly the fabricated
  // empty `UI-D8` rules out.
  const solutionsLabel =
    solutionCount === undefined
      ? t('navSolutions')
      : `${t('navSolutions')} (${String(solutionCount)})`;

  return (
    <Box
      component="nav"
      aria-label={t('navLabel')}
      data-sidebar-nav
      data-collapsed={collapsed ? 'true' : undefined}
      sx={(theme) => ({
        backgroundColor: theme.palette.nav.bg,
        color: theme.palette.nav.text,
        minHeight: '100%',
        width: collapsed ? 'var(--drawer-w-rail)' : 'var(--drawer-w)',
        maxWidth: '100%',
        py: 3,
        px: collapsed ? 1 : 1.5,
        boxSizing: 'border-box',
        transition: theme.transitions.create(['width', 'padding'], {
          duration: theme.transitions.duration.shorter,
        }),
      })}
    >
      {/* The section caption is decoration for a labelled `<nav>`; the rail
          has no room for it and loses nothing by dropping it. */}
      {!collapsed && (
        <Typography variant="overline" sx={(theme) => ({ color: theme.palette.nav.muted, px: 1 })}>
          {t('navPlatform')}
        </Typography>
      )}
      <List disablePadding>
        <NavItem
          to="/"
          end
          label={t('navHome')}
          icon={<HomeIcon fontSize="small" />}
          collapsed={collapsed}
        />
        <NavItem
          to="/solutions"
          label={solutionsLabel}
          icon={<GridIcon fontSize="small" />}
          collapsed={collapsed}
        />
      </List>
      <List disablePadding sx={{ pl: collapsed ? 0 : 2 }} data-sidebar-solutions>
        {solutions.map((solution) => (
          <NavItem
            key={solution.id}
            to={`/solutions/${solution.id}/instances`}
            label={`${solution.name} (${t('instanceCount', { count: solution.instanceCount })})`}
            icon={<SolutionGlyph name={solution.icon} fontSize="small" />}
            collapsed={collapsed}
            statusDot
          />
        ))}
      </List>

      {onToggleCollapsed !== undefined && (
        <Box sx={{ mt: 1, display: 'flex', justifyContent: collapsed ? 'center' : 'flex-end' }}>
          <RailToggle collapsed={collapsed} onToggle={onToggleCollapsed} />
        </Box>
      )}
    </Box>
  );
}

interface NavItemProps {
  to: string;
  label: string;
  icon: ReactNode;
  collapsed: boolean;
  end?: boolean;
  /**
   * Solution entries only. The dot is the solution's selection/health
   * marker — green and ringed while selected, a muted bullet otherwise —
   * so "which solution am I in" survives the labels leaving the rail.
   */
  statusDot?: boolean;
}

function NavItem({ to, label, icon, collapsed, end = false, statusDot = false }: NavItemProps) {
  // Same `to`/`end` pair the NavLink below navigates with, so the styling
  // and the link's own `.active` / `aria-current` can never disagree.
  const active = useMatch({ path: to, end }) !== null;

  const button = (
    <ListItemButton
      component={NavLink}
      to={to}
      end={end}
      // The label leaves the layout in rail mode but must not leave the
      // accessibility tree — without this the link's only accessible name
      // would be the (aria-hidden) glyph, i.e. none at all.
      aria-label={collapsed ? label : undefined}
      sx={(theme) => ({
        borderRadius: 1,
        mb: 0.5,
        px: collapsed ? 1 : 1.5,
        justifyContent: collapsed ? 'center' : 'flex-start',
        color: theme.palette.nav.text,
        borderLeft: '3px solid transparent',
        // The drawer is always the navy surface, in light mode and dark
        // alike, so `action.selected` / `action.hover` — which are mixed
        // for the *page* background — come out all but invisible on it.
        // These two are derived from the nav's own palette instead.
        '&.active': {
          borderLeftColor: theme.palette.nav.accent,
          backgroundColor: alpha(theme.palette.nav.accent, 0.14),
          color: theme.palette.common.white,
        },
        '&:hover': { backgroundColor: alpha(theme.palette.nav.text, 0.08) },
      })}
    >
      <ListItemIcon
        sx={{
          minWidth: 0,
          // The status dot overhangs the glyph's right edge, so a row that
          // carries one needs the extra step of gap or it crowds the label.
          mr: collapsed ? 0 : statusDot ? 2 : 1.5,
          color: 'inherit',
          display: 'flex',
          position: 'relative',
        }}
      >
        {icon}
        {statusDot && <StatusDot active={active} />}
      </ListItemIcon>
      {!collapsed && (
        <ListItemText
          primary={label}
          slotProps={{ primary: { noWrap: true, sx: { overflow: 'hidden' } } }}
        />
      )}
    </ListItemButton>
  );

  return (
    <ListItem disablePadding sx={{ display: 'block' }}>
      {collapsed ? (
        <MuiTooltip title={label} placement="right">
          {button}
        </MuiTooltip>
      ) : (
        button
      )}
    </ListItem>
  );
}

/**
 * Selection/health marker for a solution entry: a dot riding the corner of
 * the solution's own glyph, ringed in the drawer's background so it reads
 * as attached to the icon rather than floating over it. Attaching it to
 * the glyph rather than to the row is what makes one treatment work at
 * both widths — the glyph is the one part of the row the rail keeps.
 *
 * Decorative: the selected state is already carried by `aria-current` on
 * the link and by the accent bar, so this is never colour alone.
 */
function StatusDot({ active }: { active: boolean }) {
  return (
    <Box
      component="span"
      aria-hidden
      data-nav-status-dot
      data-active={active ? 'true' : undefined}
      sx={(theme) => ({
        width: 8,
        height: 8,
        flex: 'none',
        borderRadius: '50%',
        backgroundColor: active ? theme.palette.nav.accent : alpha(theme.palette.nav.muted, 0.6),
        position: 'absolute',
        right: -3,
        bottom: -1,
        // The inner ring punches the dot out of the glyph; the outer glow
        // is what stops the selected one reading as a stray pixel.
        boxShadow: active
          ? `0 0 0 2px ${theme.palette.nav.bg}, 0 0 0 4px ${alpha(theme.palette.nav.accent, 0.28)}`
          : `0 0 0 2px ${theme.palette.nav.bg}`,
        transition: theme.transitions.create(['background-color', 'box-shadow'], {
          duration: theme.transitions.duration.shorter,
        }),
      })}
    />
  );
}

function RailToggle({ collapsed, onToggle }: { collapsed: boolean; onToggle: () => void }) {
  const label = collapsed ? t('navExpand') : t('navCollapse');
  return (
    <MuiTooltip title={label} placement="right">
      <Box
        component="button"
        type="button"
        onClick={onToggle}
        aria-label={label}
        aria-expanded={!collapsed}
        sx={(theme) => ({
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 32,
          height: 32,
          borderRadius: 1,
          border: '1px solid',
          borderColor: alpha(theme.palette.nav.text, 0.24),
          backgroundColor: 'transparent',
          color: theme.palette.nav.text,
          cursor: 'pointer',
          '&:hover': { backgroundColor: theme.palette.action.hover },
        })}
      >
        {collapsed ? <ChevronRightIcon fontSize="small" /> : <ChevronLeftIcon fontSize="small" />}
      </Box>
    </MuiTooltip>
  );
}
