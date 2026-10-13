/**
 * The left rail that jumps to a Designer section (task 050, design §10,
 * UI §A.8.7): "each with either a field count or a red/amber dot.
 * Selecting one expands the section and scrolls its first field into
 * view." Pair with `useSectionJump` for the expand + scroll + focus
 * behaviour — this component is presentation only.
 *
 * UI §A.8.7 also describes "components (multi-component only)" as a
 * grouping level above sections. `ViewSection` (FE-046's contract) has no
 * component-instance identity to group by — proven by the
 * `multi-component-selection` fixture, where two different component
 * instances project two `ViewSection`s that are identical in every field
 * *except* the values inside them (same title "General", same
 * `renderedPath` prefix, since `configPath` is a per-component-*type*
 * property, not per-instance). Grouping by component is therefore left
 * undone here rather than faked from data that can't support it; this
 * renders correctly today because the only assembled scenario is
 * single-component (Cache, the v1 target). Multi-component nav needs a
 * contract change (an instance identifier on `ViewSection`) — flagged for
 * whichever task first assembles a real multi-component Designer page
 * (`FE-063`, Composer canvas).
 *
 * **Rail vs. drawer** (task 108, `FE-D29`): a persistent rail at `lg` and
 * up; below that, a *"Jump to section"* button opens the identical list
 * inside a `Drawer` — same breakpoint (`up('lg')`) and the same
 * permanent/temporary `Drawer` pattern `AppShell.tsx` (task 106) already
 * established, so this is the second, not the first, place that pattern
 * appears. The block/warn dots are never dropped: both branches render
 * the exact same `NavList`, just in a different container — the dots are
 * how an operator finds the failing field (task's own acceptance line),
 * so they survive the collapse by construction, not by being reproduced.
 * Selecting a section closes the drawer (`onJump` is wrapped, not
 * replaced), the same "close on the action that made the destination
 * reachable" rule `AppShell`'s own overlay drawer follows for navigation.
 */
import { Box, Drawer, Typography, useMediaQuery, useTheme } from '@mui/material';
import type { ReactElement } from 'react';
import { useState } from 'react';

import { t } from '@shared/i18n';
import { Button } from '@shared/ui';

import { groupSectionFields } from '../SectionCard/sectionFieldGroups';

import type { SectionNavProps } from './SectionNav.types';

function NavList({ sections, onJump }: SectionNavProps): ReactElement {
  const theme = useTheme();
  const errColor = theme.palette.tone.err.main;
  const warnColor = theme.palette.tone.warn.main;

  return (
    <Box component="nav" aria-label={t('sectionNavLabel')} sx={{ display: 'flex', flexDirection: 'column', gap: 0.25 }}>
      {sections.map((section, index) => {
        const { applicable, blockCount, warnCount } = groupSectionFields(section);
        const statusText =
          blockCount > 0
            ? t('sectionCardBlockCount', { count: blockCount })
            : warnCount > 0
              ? t('sectionCardWarnCount', { count: warnCount })
              : t('sectionCardFieldCount', { count: applicable.length });

        return (
          <Box
            key={index}
            component="button"
            type="button"
            onClick={() => onJump(index)}
            aria-label={`${section.title} — ${statusText}`}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              width: '100%',
              px: 1,
              py: 0.75,
              border: 'none',
              background: 'none',
              cursor: 'pointer',
              font: 'inherit',
              textAlign: 'left',
              color: 'text.primary',
              borderRadius: 1,
              '&:hover': { backgroundColor: 'action.hover' },
            }}
          >
            <Typography
              variant="body2"
              sx={{ flexGrow: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            >
              {section.title}
            </Typography>
            {blockCount > 0 ? (
              <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: errColor, flexShrink: 0 }} />
            ) : warnCount > 0 ? (
              <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '50%', backgroundColor: warnColor, flexShrink: 0 }} />
            ) : (
              <Typography variant="caption" color="text.secondary" aria-hidden>
                {applicable.length}
              </Typography>
            )}
          </Box>
        );
      })}
    </Box>
  );
}

export function SectionNav({ sections, onJump }: SectionNavProps): ReactElement {
  const theme = useTheme();
  const persistentRail = useMediaQuery(theme.breakpoints.up('lg'));
  const [drawerOpen, setDrawerOpen] = useState(false);

  if (persistentRail) {
    return <NavList sections={sections} onJump={onJump} />;
  }

  const handleJump = (index: number): void => {
    onJump(index);
    setDrawerOpen(false);
  };

  return (
    <>
      <Button variant="outlined" onClick={() => setDrawerOpen(true)}>
        {t('sectionNavJumpButton')}
      </Button>
      <Drawer anchor="left" open={drawerOpen} onClose={() => setDrawerOpen(false)}>
        <Box sx={{ width: 280, maxWidth: '85vw', p: 2 }}>
          <NavList sections={sections} onJump={handleJump} />
        </Box>
      </Drawer>
    </>
  );
}
