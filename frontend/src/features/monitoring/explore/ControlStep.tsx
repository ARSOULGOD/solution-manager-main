/**
 * One numbered, gated step in Explore's control column (task 077, Dec
 * `DM-D9` `DM-D23`, Design §11, `dynamic-metrics` §9.1/§11.1, UI §A.10 —
 * mirrors `docs/design/frontend/reference/app/explore.jsx`'s `.ctl-step`
 * structure and `theme.css`'s `.ctl-step`/`.cs-h`/`.num` values).
 *
 * Presentational only: the lock *decision* is `exploreGating.ts`'s job,
 * not this component's. `locked`/`lockedReason` are a discriminated
 * union — a caller cannot pass `locked` without `lockedReason` — so
 * "every locked step states why" is a compile error to violate, not a
 * convention to remember.
 *
 * **Deliberate correction of the reference.** Its own
 * `.ctl-step.locked{opacity:.5;pointer-events:none}` dims a locked step
 * but states no reason anywhere, and `pointer-events:none` alone leaves
 * any focusable control inside still reachable by keyboard — dimmed but
 * not actually disabled. This component renders the reason at full
 * opacity (never dimmed away) and only dims `children`; it does not rely
 * on `pointer-events:none` to do the disabling. Tasks 078-081, which fill
 * `children` with real inputs, are responsible for passing `disabled`
 * down to those inputs when `locked` is true — a wrapper `Box` cannot
 * disable arbitrary children it did not create.
 *
 * **Task 109**: below `lg` this becomes one leaf of Explore's vertical
 * accordion. A real `<button>` with `aria-expanded`, not MUI `Accordion`
 * — the same disclosure idiom `SectionCard`/`RolloutCard` already use in
 * the Designer feature, rather than introducing a second one here. The
 * header (badge, title, subtitle, and — critically — the locked reason)
 * stays outside the collapsible region: "a locked step that loses its
 * reason is worse than a locked step" (task spec) means collapsing must
 * never hide *why* a step is locked, only its (disabled) controls. At
 * `lg` and up this renders exactly as before — no button semantics, no
 * chevron, always expanded — so nothing about the two-column desktop
 * layout changes.
 */
import { Box, SvgIcon, Typography } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useState } from 'react';
import type { ReactNode } from 'react';

// Inlined, not `@mui/icons-material` (not a project dependency) — same
// ruling `SectionCard.tsx`/`RolloutCard.tsx` already record for their own
// expander icon.
function ExpanderIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M8 5l8 7-8 7V5z" />
    </SvgIcon>
  );
}

export interface ControlStepBaseProps {
  /** 1-based step number, rendered in the numbered badge. */
  step: number;
  title: string;
  /** e.g. "single-select", "to a cap" — trailing, de-emphasised annotation. */
  subtitle?: string;
  children: ReactNode;
}

export type ControlStepProps =
  | (ControlStepBaseProps & { locked?: false; lockedReason?: undefined })
  | (ControlStepBaseProps & { locked: true; lockedReason: string });

export function ControlStep({
  step,
  title,
  subtitle,
  locked = false,
  lockedReason,
  children,
}: ControlStepProps) {
  const theme = useTheme();
  const isAccordion = useMediaQuery(theme.breakpoints.down('lg'));
  const [expanded, setExpanded] = useState(true);
  const showChildren = !isAccordion || expanded;

  const badgeBackground = locked
    ? theme.palette.action.disabledBackground
    : alpha(theme.palette.primary.main, 0.12);
  const badgeColor = locked ? theme.palette.text.disabled : theme.palette.primary.main;

  return (
    <Box sx={{ mb: '22px' }} data-control-step={step} data-locked={locked}>
      <Box
        component={isAccordion ? 'button' : 'div'}
        type={isAccordion ? 'button' : undefined}
        onClick={isAccordion ? () => setExpanded((value) => !value) : undefined}
        aria-expanded={isAccordion ? expanded : undefined}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          mb: '10px',
          width: '100%',
          border: 'none',
          background: 'none',
          p: 0,
          font: 'inherit',
          textAlign: 'left',
          color: 'inherit',
          ...(isAccordion ? { cursor: 'pointer' } : {}),
        }}
      >
        <Box
          sx={{
            width: 18,
            height: 18,
            flexShrink: 0,
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 11,
            backgroundColor: badgeBackground,
            color: badgeColor,
          }}
        >
          {step}
        </Box>
        <Typography
          component="span"
          sx={{
            fontSize: 11,
            textTransform: 'uppercase',
            letterSpacing: '.06em',
            fontWeight: 700,
            color: theme.palette.text.disabled,
          }}
        >
          {title}
        </Typography>
        {subtitle !== undefined && (
          <Typography
            component="span"
            sx={{ fontSize: 11, color: theme.palette.text.secondary, fontWeight: 400 }}
          >
            · {subtitle}
          </Typography>
        )}
        {isAccordion && (
          <>
            <Box sx={{ flexGrow: 1 }} />
            <ExpanderIcon
              fontSize="small"
              sx={{ transform: expanded ? 'rotate(90deg)' : 'none', flexShrink: 0 }}
            />
          </>
        )}
      </Box>
      {/* The reason a locked step is locked is never gated behind expansion
          — see this file's header. */}
      {locked && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
          {lockedReason}
        </Typography>
      )}
      {showChildren && <Box sx={{ opacity: locked ? 0.5 : 1 }}>{children}</Box>}
    </Box>
  );
}
