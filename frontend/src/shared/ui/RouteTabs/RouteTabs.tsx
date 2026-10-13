/**
 * Tabs that are links (FE-018): deep-linkable and middle-clickable.
 * On a tab change, focus moves into the panel (design §18).
 *
 * Built from NavLink rather than MUI `Tabs`, because MUI v9 nests a
 * second `role="tablist"` inside `Tabs` (verified in the rendered DOM)
 * which fails axe `aria-required-children`.
 */
import { Box } from '@mui/material';
import { useEffect, useRef } from 'react';
import { NavLink, useLocation } from 'react-router';

import type { RouteTabsProps } from './RouteTabs.types';

export function RouteTabs({ tabs, children, trailing }: RouteTabsProps) {
  const location = useLocation();
  const panelRef = useRef<HTMLDivElement>(null);
  // `end` opts a tab out of prefix matching (task 130). Without it a tab
  // whose path is a prefix of a sibling's matches the sibling's route too,
  // and `find` returns the first match — so `/monitoring/explore` would
  // light up the `/monitoring` tab. Mirrors `NavLink`'s `end`.
  const selected = tabs.find((tab) =>
    tab.end === true
      ? location.pathname === tab.to
      : location.pathname === tab.to || location.pathname.startsWith(`${tab.to}/`),
  );
  const value = selected?.to ?? tabs[0]?.to;
  const previous = useRef(value);

  useEffect(() => {
    if (previous.current !== value) {
      previous.current = value;
      panelRef.current?.focus();
    }
  }, [value]);

  return (
    <div>
      {/* The rule moves to this row so `trailing` sits ON the tab line
          rather than above or below it. The `tablist` itself keeps only
          tabs as children (task 131). */}
      <Box sx={{ display: 'flex', alignItems: 'center', borderBottom: 1, borderColor: 'divider' }}>
        <Box role="tablist" sx={{ display: 'flex', gap: 1, flex: '1 1 auto', minWidth: 0 }}>
          {tabs.map((tab) => {
            const isSelected = tab.to === value;
            return (
              <Box
                key={tab.to}
                component={NavLink}
                to={tab.to}
                role="tab"
                aria-selected={isSelected}
                tabIndex={isSelected ? 0 : -1}
                sx={(theme) => ({
                  px: 2,
                  py: 1.5,
                  textDecoration: 'none',
                  color: isSelected ? theme.palette.primary.main : theme.palette.text.secondary,
                  borderBottom: '2px solid',
                  borderColor: isSelected ? 'primary.main' : 'transparent',
                })}
              >
                {tab.label}
              </Box>
            );
          })}
        </Box>
        {trailing !== undefined && (
          <Box sx={{ display: 'flex', alignItems: 'center', pl: 1 }}>{trailing}</Box>
        )}
      </Box>
      <div ref={panelRef} role="tabpanel" tabIndex={-1} data-route-tab-panel>
        {children}
      </div>
    </div>
  );
}
