/**
 * §12 / A.10.6: tooltip and legend both carry a "state tag" from the same
 * five-member vocabulary — `gap` (synthetic, cursor-relative, task 074) plus
 * one tag per non-`ok` `SeriesState`. `ok` renders no tag, matching the
 * types.ts comment that `ok` is "the common case, visited by no rule".
 * Label text and tone are not given numerically anywhere in the design (same
 * gap as `ChartRenderer.tsx`'s `DIMMED_LINE_OPACITY`) — mapped onto the
 * existing `tone` vocabulary (`palette.ts`: "Chip/StateChip tones...
 * nothing here invents a colour") rather than a fresh colour: `type-suspect`
 * reuses `tone.warn`, matching A.10.1's "persistent warn alert" framing for
 * the same state elsewhere in the app; `pending-config-change` reuses
 * `tone.info`, matching `palette.pending`'s own hue; `inactive-replica` and
 * `unverified-durability` both reuse `tone.idle`, matching
 * `palette.health.inactiveReplica` and `palette.unverified`'s hues
 * respectively — sharing one tone between two distinct tags is a
 * deliberate "no invented colour" trade, not an oversight; the label text
 * is what disambiguates them.
 *
 * Its own file (not `ChartRenderer.tsx`, task 074's original home) because
 * task 075's `ChartDataTable` needs the same mapping for the data table's
 * per-series state chip — the tooltip, legend and table must show one
 * label per state, never three independent copies — and a `ChartDataTable`
 * import from `ChartRenderer.tsx` (the `echarts`-importing, FE-D16-exempt
 * file) would be circular the other way, since `ChartRenderer` renders
 * `ChartDataTable` itself.
 */
import type { SeriesState } from '@contracts/metrics/types';

export interface StateTag {
  /** Legend rich-text token name — must be a bare identifier (zrender rich-text key). */
  token: string;
  label: string;
  tone: 'neutral' | 'idle' | 'info' | 'warn';
}

export const GAP_TAG: StateTag = { token: 'gapTag', label: 'gap', tone: 'neutral' };

export const STATE_TAG: Partial<Record<SeriesState, StateTag>> = {
  'inactive-replica': { token: 'inactiveTag', label: 'inactive replica', tone: 'idle' },
  'pending-config-change': { token: 'pendingTag', label: 'pending', tone: 'info' },
  'type-suspect': { token: 'suspectTag', label: 'type-suspect', tone: 'warn' },
  'unverified-durability': { token: 'bestEffortTag', label: 'best-effort', tone: 'idle' },
};

export const ALL_STATE_TAGS: readonly StateTag[] = [GAP_TAG, ...Object.values(STATE_TAG)];
