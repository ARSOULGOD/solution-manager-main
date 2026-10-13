/**
 * One canvas node (task 063, design §10, UI §A.8.18) — selectable and
 * movable by pointer or keyboard alike (⚠️ "keyboard is not optional: a
 * pointer-only canvas fails AA").
 *
 * The whole node is a native `<button>`: focus, click and Enter/Space
 * selection come from the browser for free (the same reasoning
 * `StrategyPicker`, task 061, gives for using `RadioGroup` over a
 * hand-rolled `role="radio"` div). Its own `onKeyDown` only intercepts the
 * four arrow keys — every other key (Tab, Enter, Space) falls through to
 * the button's native behaviour untouched. A nested pointer-drag handle
 * inside a `<button>` is valid: `pointerdown` on a child still reaches
 * that child's own handler; only the button element owns click/keyboard
 * semantics.
 *
 * Position is presentation-only (`UI-D20`) — `onMove` never touches
 * `DraftValues`, and nothing here marks the form dirty.
 */
import { Box, Typography } from '@mui/material';
import type { KeyboardEvent, PointerEvent as ReactPointerEvent, ReactElement } from 'react';

import type { ComponentSelectionEntry } from '@contracts/view-model/types';
import { t } from '@shared/i18n';
import { Chip } from '@shared/ui';

import { ComponentRoleIcon } from './composerIcons';
import { composerRoleLabel } from './composerRoleLabel';
import { directionForKey, nudgePosition, type NodePosition } from './nodeCanvas';

const NODE_WIDTH = 220;

export interface ComposerNodeProps {
  entry: ComponentSelectionEntry;
  position: NodePosition;
  selected: boolean;
  fieldCount: number;
  requiredEmptyCount: number;
  onSelect: () => void;
  onMove: (position: NodePosition) => void;
}

export function ComposerNode({
  entry,
  position,
  selected,
  fieldCount,
  requiredEmptyCount,
  onSelect,
  onMove,
}: ComposerNodeProps): ReactElement {
  const roleLabel = composerRoleLabel(entry.role);
  const fieldsLabel = t('composerNodeFieldCount', { count: fieldCount });
  const requiredLabel =
    requiredEmptyCount > 0
      ? t('composerNodeRequiredCount', { count: requiredEmptyCount })
      : t('composerNodeOkLabel');

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    const direction = directionForKey(event.key);
    if (direction === undefined) {
      return;
    }
    event.preventDefault();
    onMove(nudgePosition(position, direction));
  }

  function startDrag(event: ReactPointerEvent<HTMLDivElement>): void {
    event.stopPropagation();
    const start = { x: event.clientX, y: event.clientY };
    const origin = position;

    function handleMove(moveEvent: PointerEvent): void {
      onMove({
        x: Math.max(0, origin.x + (moveEvent.clientX - start.x)),
        y: Math.max(0, origin.y + (moveEvent.clientY - start.y)),
      });
    }
    function handleUp(): void {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
    }
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
  }

  return (
    <Box
      component="button"
      type="button"
      data-node-id={entry.instanceId}
      onClick={onSelect}
      onKeyDown={handleKeyDown}
      aria-pressed={selected}
      aria-label={`${roleLabel} · ${entry.type} · ${fieldsLabel} · ${requiredLabel}`}
      sx={{
        position: 'absolute',
        left: position.x,
        top: position.y,
        width: NODE_WIDTH,
        display: 'block',
        textAlign: 'left',
        p: 0,
        font: 'inherit',
        backgroundColor: 'background.paper',
        border: 2,
        borderColor: selected ? 'primary.main' : 'divider',
        borderRadius: 2,
        boxShadow: selected ? 4 : 1,
        cursor: 'pointer',
      }}
    >
      <Box
        onPointerDown={startDrag}
        title={t('composerNodeDragHint')}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          p: 1.25,
          borderBottom: 1,
          borderColor: 'divider',
          cursor: 'grab',
        }}
      >
        <Box
          aria-hidden
          sx={{
            width: 32,
            height: 32,
            borderRadius: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            color: 'primary.contrastText',
            backgroundColor: entry.role === 'sink' ? 'secondary.main' : 'primary.main',
          }}
        >
          <ComponentRoleIcon role={entry.role} fontSize="small" />
        </Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography
            variant="caption"
            sx={{
              display: 'block',
              fontWeight: 700,
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
            }}
          >
            {roleLabel}
          </Typography>
          <Typography
            variant="body2"
            sx={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontWeight: 600,
            }}
          >
            {entry.type}
          </Typography>
        </Box>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', p: 1.25 }}>
        <Typography variant="caption" color="text.secondary">
          {fieldsLabel}
        </Typography>
        {requiredEmptyCount > 0 ? (
          <Chip label={String(requiredEmptyCount)} tone="err" />
        ) : (
          <Chip label={t('composerNodeOkLabel')} tone="ok" />
        )}
      </Box>
    </Box>
  );
}
