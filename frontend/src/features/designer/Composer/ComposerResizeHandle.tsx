/**
 * A draggable divider between two Composer panes (task: Designer width/
 * resize follow-up). Local, presentation-only — like node position in
 * `ComposerNode.tsx`, it never touches `DraftValues` and never marks the
 * form dirty. Pointer-driven drag follows the same
 * `window.addEventListener('pointermove'/'pointerup', …)` pattern
 * `ComposerNode.tsx`'s own `startDrag` uses, plus arrow-key nudging so
 * the divider is keyboard-operable too (⚠️ "keyboard is not optional",
 * that module's own words) — `role="separator"` with `aria-valuenow`.
 */
import { Box } from '@mui/material';
import type { KeyboardEvent, PointerEvent as ReactPointerEvent, ReactElement } from 'react';

export interface ComposerResizeHandleProps {
  /** Human-readable label for the pane this handle resizes, for `aria-label`. */
  label: string;
  width: number;
  min: number;
  max: number;
  onResize: (next: number) => void;
  /**
   * Dragging right grows the pane for a handle on its right edge
   * (palette) but shrinks it for a handle on its left edge (inspector).
   */
  direction: 'grow-right' | 'grow-left';
}

const NUDGE_PX = 16;

export function ComposerResizeHandle({
  label,
  width,
  min,
  max,
  onResize,
  direction,
}: ComposerResizeHandleProps): ReactElement {
  function clamp(value: number): number {
    return Math.min(max, Math.max(min, value));
  }

  function startDrag(event: ReactPointerEvent<HTMLDivElement>): void {
    const startX = event.clientX;
    const startWidth = width;

    function handleMove(moveEvent: PointerEvent): void {
      const delta = moveEvent.clientX - startX;
      const signedDelta = direction === 'grow-right' ? delta : -delta;
      onResize(clamp(startWidth + signedDelta));
    }
    function handleUp(): void {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
    }
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const growKey = direction === 'grow-right' ? 'ArrowRight' : 'ArrowLeft';
    const shrinkKey = direction === 'grow-right' ? 'ArrowLeft' : 'ArrowRight';
    if (event.key === growKey) {
      event.preventDefault();
      onResize(clamp(width + NUDGE_PX));
    } else if (event.key === shrinkKey) {
      event.preventDefault();
      onResize(clamp(width - NUDGE_PX));
    }
  }

  return (
    <Box
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={width}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      onPointerDown={startDrag}
      onKeyDown={handleKeyDown}
      sx={{
        width: 6,
        flexShrink: 0,
        cursor: 'col-resize',
        touchAction: 'none',
        position: 'relative',
        '&:hover, &:focus-visible': {
          backgroundColor: 'action.hover',
        },
        '&:focus-visible': {
          outline: 2,
          outlineColor: 'primary.main',
          outlineOffset: -2,
        },
        '&::after': {
          content: '""',
          position: 'absolute',
          top: 0,
          bottom: 0,
          left: '50%',
          width: '1px',
          backgroundColor: 'divider',
          transform: 'translateX(-50%)',
        },
      }}
    />
  );
}
