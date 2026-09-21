/**
 * White app bar: breadcrumbs, organization display, theme toggle, support menu,
 * account menu (task 036, task 106, task 111, design §9, UI §A.2). Sticky
 * so it stays visible while `<main>` scrolls independently.
 *
 * Responsive (task 106, `FE-D29`): below `lg` a labelled menu button opens
 * `AppShell`'s overlay drawer. Below `md` the organization display, theme
 * toggle and support menu fold into `AccountMenu` instead of disappearing —
 * `I5`'s identity display is never dropped, only relocated — and the wordmark
 * (`Brand`) appears here since the permanent drawer that normally carries
 * it is hidden.
 *
 * The nav toggle button (`navOpen`/`onToggleNav`, state owned by
 * `AppShell`) opens and closes the **overlay** drawer, and so renders only
 * where that drawer exists — below `lg` (`showNavToggle`). At `lg` and up
 * the docked drawer collapses to an icon rail that is always on screen and
 * carries its own collapse/expand control (`SidebarNav`), so navigation
 * there can never become unreachable — the task-106 regression task 111
 * fixed. A second, identically-labelled toggle up here would only be a
 * duplicate name in the accessibility tree.
 */
import { AppBar as MuiAppBar, Toolbar, useMediaQuery, useTheme } from '@mui/material';

import { useAuth } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { tokens } from '@shared/theme';
import { Breadcrumbs, IconButton, type MenuEntry } from '@shared/ui';

import { AccountMenu } from './AccountMenu';
import { Brand } from './Brand';
import { MenuIcon } from './icons';
import { OrganizationDisplay } from './OrganizationDisplay';
import { SupportMenu, SupportMenuContent } from './SupportMenu';
import { ThemeToggle } from './ThemeToggle';

export interface AppBarProps {
  /** Is the overlay drawer open. Meaningless at `lg` and up, where `showNavToggle` is false. */
  navOpen: boolean;
  onToggleNav: () => void;
  /** True only below `lg`, where the drawer is an overlay that needs an opener. */
  showNavToggle: boolean;
}

export function AppBar({ navOpen, onToggleNav, showNavToggle }: AppBarProps) {
  const { identity } = useAuth();
  const theme = useTheme();
  const collapsed = useMediaQuery(theme.breakpoints.down('md'));

  const collapsedEntries: MenuEntry[] = collapsed
    ? [
        {
          kind: 'content',
          content: (
            <>
              {identity !== null && <OrganizationDisplay organization={identity.organization} />}
              <ThemeToggle />
            </>
          ),
        },
        { kind: 'caption', label: t('supportCaption') },
        { kind: 'content', content: <SupportMenuContent /> },
      ]
    : [];

  return (
    <MuiAppBar
      position="sticky"
      color="inherit"
      elevation={0}
      sx={{
        backgroundColor: 'background.paper',
        borderBottom: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Toolbar
        sx={{
          height: tokens.console.appbarHeight,
          minHeight: `${tokens.console.appbarHeight} !important`,
          gap: 2,
        }}
      >
        {showNavToggle && (
          <IconButton
            aria-label={navOpen ? t('navCloseMenu') : t('navOpenMenu')}
            aria-expanded={navOpen}
            onClick={onToggleNav}
          >
            <MenuIcon />
          </IconButton>
        )}
        {collapsed && <Brand tone="default" />}
        <Breadcrumbs />
        <div style={{ flex: 1 }} />
        {!collapsed && identity !== null && (
          <OrganizationDisplay organization={identity.organization} />
        )}
        {!collapsed && <ThemeToggle />}
        {!collapsed && <SupportMenu />}
        <AccountMenu extraEntries={collapsedEntries} />
      </Toolbar>
    </MuiAppBar>
  );
}
