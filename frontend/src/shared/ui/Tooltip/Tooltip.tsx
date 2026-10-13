/**
 * Keyboard-reachable by construction (task 014 acceptance: "every tooltip
 * reachable by keyboard"). MUI's own `Tooltip` opens on focus as well as
 * hover (verified against `@mui/material/Tooltip` source, 2026-08-20),
 * but that only fires if its child can actually *receive* focus — a bare
 * icon or a disabled control can't. Wrapping the trigger in a
 * `tabIndex={0}` span guarantees it always can, covering both the plain
 * "explain this" case (`InfoTip`) and design's documented "explain why
 * this disabled control is disabled" case (`FE-D9`, `Button` task 011)
 * without asking every caller to remember it.
 *
 * A tooltip is never the only carrier of a blocking reason — the reason
 * must also be in text somewhere on the page (design §9's rule; not
 * mechanically enforceable here since this component has no way to know
 * whether its content is the *only* copy of a reason).
 *
 * MUI's default (`describeChild={false}`) puts the title on the trigger
 * as `aria-label` rather than `aria-describedby` — correct for wrapping
 * nameless content like a bare icon, since the tooltip text becomes the
 * trigger's only accessible name. But `aria-label` is only a *permitted*
 * attribute on elements with a role that supports naming; a plain `span`
 * has no such role, which axe's `aria-prohibited-attr` rule correctly
 * flags (found via the a11y test, not assumed). `role="button"` is the
 * common, axe-clean choice for an on-demand info trigger.
 */
import { Box, Tooltip as MuiTooltip } from '@mui/material';

import type { TooltipProps } from './Tooltip.types';

export function Tooltip({ title, children }: TooltipProps) {
  return (
    <MuiTooltip title={title}>
      <Box component="span" role="button" tabIndex={0} sx={{ display: 'inline-flex' }}>
        {children}
      </Box>
    </MuiTooltip>
  );
}
