/**
 * Pure logic behind the composer canvas (task 063, Design §10, UI §A.8.18):
 * default node layout, the derived source→sink link, and the keyboard
 * nudge that stands in for pointer drag. Split from the rendering
 * components the same way `majorityGeneration.ts` (task 060) and
 * `strategyAvailability.ts` (task 061) are — every branch here is a
 * fixture case in `nodeCanvas.test.ts`, not something only provable by
 * rendering the canvas.
 *
 * Positions are presentation state only (`UI-D20`): nothing here reads or
 * writes `DraftValues`, and no caller may fold a `NodePositions` map into
 * a save payload or a config hash.
 */
import type { ComponentSelection } from '@contracts/view-model/types';

export interface NodePosition {
  x: number;
  y: number;
}

export type NodePositions = Readonly<Record<string, NodePosition>>;

export interface ComposerLink {
  from: string;
  to: string;
}

const COLUMN_X: Readonly<Record<'source' | 'sink' | 'other', number>> = {
  source: 40,
  other: 260,
  sink: 480,
};
const ROW_START_Y = 40;
const ROW_HEIGHT = 140;

function columnFor(role: string | undefined): 'source' | 'sink' | 'other' {
  if (role === 'source') {
    return 'source';
  }
  if (role === 'sink') {
    return 'sink';
  }
  return 'other';
}

/**
 * One node per declared instance, columned by role (source left, sink
 * right, anything else in the middle) and stacked top-to-bottom within its
 * column — a generic layout, not one keyed off a specific component
 * `type` (the same "never hard-coded FTP/SFTP/S3" rule the palette
 * follows, design §10 UI §A.8.18).
 */
export function defaultNodePositions(componentSelection: ComponentSelection): NodePositions {
  const rowByColumn: Partial<Record<'source' | 'sink' | 'other', number>> = {};
  const result: Record<string, NodePosition> = {};
  for (const entry of componentSelection) {
    const column = columnFor(entry.role);
    const row = rowByColumn[column] ?? 0;
    result[entry.instanceId] = { x: COLUMN_X[column], y: ROW_START_Y + row * ROW_HEIGHT };
    rowByColumn[column] = row + 1;
  }
  return result;
}

/**
 * `UI-D20`'s "derived source→sink link" — every declared `source` paired
 * with every declared `sink`. No independent routing choice exists in the
 * contract (`ComponentSelectionEntry` carries no edge/link data), so this
 * is the only link a caller could possibly persist, and it never is.
 */
export function derivedLinks(componentSelection: ComponentSelection): readonly ComposerLink[] {
  const sources = componentSelection.filter((entry) => entry.role === 'source');
  const sinks = componentSelection.filter((entry) => entry.role === 'sink');
  const links: ComposerLink[] = [];
  for (const source of sources) {
    for (const sink of sinks) {
      links.push({ from: source.instanceId, to: sink.instanceId });
    }
  }
  return links;
}

export type NudgeDirection = 'up' | 'down' | 'left' | 'right';

export const NODE_KEYBOARD_STEP = 16;

const ARROW_KEY_DIRECTIONS: Readonly<Record<string, NudgeDirection>> = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
};

/** `undefined` for any key that isn't one of the four arrow keys — the caller's cue to fall through to default keyboard handling (e.g. the button's own Enter/Space click). */
export function directionForKey(key: string): NudgeDirection | undefined {
  return ARROW_KEY_DIRECTIONS[key];
}

/** The keyboard equivalent of a pointer drag (design §10 UI §A.8.18's "keyboard is not optional") — clamped to non-negative so a node can't be nudged off the scrollable canvas's top-left origin. */
export function nudgePosition(
  position: NodePosition,
  direction: NudgeDirection,
  step: number = NODE_KEYBOARD_STEP,
): NodePosition {
  switch (direction) {
    case 'up':
      return { ...position, y: Math.max(0, position.y - step) };
    case 'down':
      return { ...position, y: position.y + step };
    case 'left':
      return { ...position, x: Math.max(0, position.x - step) };
    case 'right':
      return { ...position, x: position.x + step };
  }
}

/**
 * FE-063's own "build behind a package-capability check": the composer
 * only earns its keep for a multi-component instance (Cache = one node,
 * File Mesh = source + sink). A single-component solution has nothing for
 * a second pane or a derived link to show, and stays on the flat form
 * (task 052 onward) as its only view.
 */
export function isComposerEligible(componentSelection: ComponentSelection): boolean {
  return componentSelection.length > 1;
}
