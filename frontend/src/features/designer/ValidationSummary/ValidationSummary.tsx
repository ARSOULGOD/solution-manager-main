/**
 * Form-level rollup of every offending field across all Designer sections
 * (task 054, design §10/§18, UI §A.8.9; reference `ValidationSummary` in
 * `docs/design/frontend/reference/app/designer_panels.jsx`). One chip per
 * field; clicking it reuses task 050's own `useSectionJump.jumpToField`
 * (expand the section, scroll the field into view, focus it) rather than a
 * second jump mechanism.
 *
 * `Alert` (task 014) already carries the one behavioural rule this task
 * needs: `role="alert"` fires only for `severity="err"`, never for `"warn"`
 * — so a warn-only summary doesn't interrupt a screen reader the way a real
 * blocker does. Severity here is `err` whenever at least one field blocks,
 * `warn` otherwise (never rendered when both lists are empty).
 *
 * The both-tiers header is two separate catalogue sentences, not one string
 * built by concatenating the block and warn headers — design §18 rules out
 * gluing fragments into a sentence (see `messages.en.ts`'s own module
 * doc), so the warn count gets its own secondary sentence
 * (`validationSummaryWarnAlso`) below the primary header instead.
 */
import { Box, SvgIcon, Typography, useTheme } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import { Alert } from '@shared/ui';

import { collectValidationEntries } from './collectValidationEntries';
import type { ValidationEntry } from './collectValidationEntries';
import type { ValidationSummaryProps } from './ValidationSummary.types';

// Inlined, not `@mui/icons-material` — same ruling as `SectionCard`'s own
// expander/add/remove icons (not a project dependency). Path matches the
// reference's own `arrowRight` glyph (`docs/design/frontend/reference/app/icons.jsx`).
function ArrowIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M5 12h14M13 6l6 6-6 6" />
    </SvgIcon>
  );
}

export function ValidationSummary({ sections, onJump }: ValidationSummaryProps): ReactElement | null {
  const theme = useTheme();
  const { blocks, warns } = collectValidationEntries(sections);

  if (blocks.length === 0 && warns.length === 0) {
    return null;
  }

  const blocking = blocks.length > 0;
  const header = blocking
    ? t('validationSummaryBlockHeader', { count: blocks.length })
    : t('validationSummaryWarnHeader', { count: warns.length });

  const chip = (entry: ValidationEntry, tone: 'err' | 'warn') => {
    const { main, bg } = theme.palette.tone[tone];
    return (
      <Box
        key={`${entry.sectionIndex}-${entry.fieldKey}`}
        component="button"
        type="button"
        onClick={() => onJump(entry.sectionIndex, entry.fieldKey)}
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          borderRadius: 4,
          border: `1px solid ${main}`,
          px: 1,
          py: 0.25,
          cursor: 'pointer',
          font: 'inherit',
          fontSize: theme.typography.caption.fontSize,
          fontWeight: theme.typography.fontWeightMedium,
          backgroundColor: bg,
          color: main,
        }}
      >
        <ArrowIcon sx={{ fontSize: 12 }} />
        {entry.label}
      </Box>
    );
  };

  return (
    <Alert severity={blocking ? 'err' : 'warn'} title={header} fullWidth>
      {blocking && warns.length > 0 ? (
        <Typography variant="caption" color="text.secondary">
          {t('validationSummaryWarnAlso', { count: warns.length })}
        </Typography>
      ) : null}
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1 }}>
        {blocks.map((entry) => chip(entry, 'err'))}
        {warns.map((entry) => chip(entry, 'warn'))}
      </Box>
    </Alert>
  );
}
