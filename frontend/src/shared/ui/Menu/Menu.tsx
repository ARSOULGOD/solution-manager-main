/**
 * Menu wrapper with caption rows for group labels (UI §A.1 `mi-cap`).
 * MUI's Menu already implements roving focus, Escape-to-close and
 * outside-click-to-close (verified against `@mui/material/Menu`). The
 * trigger receives `aria-haspopup` / `aria-expanded`; closing restores
 * focus to it.
 *
 * **Rows are `.mi`, not bare text.** The reference popovers
 * (`reference/app/shell.jsx`, `.mi` in `reference/theme.css`) draw an
 * action row as a 36px-high rounded strip — leading icon, label, optional
 * trailing tag — that lights up on hover and in its selected state. MUI's
 * default `MenuItem` is a full-bleed text row with no icon slot and no
 * radius, which is what the account menu was rendering. The `.mi`
 * geometry is applied here, once, rather than at each call site: gap 11px,
 * padding 9px/11px, radius 6px, and 6px of padding on the list so a
 * hovered row's rounded corners have somewhere to sit.
 */
import { Box, Divider, ListSubheader, Menu as MuiMenu, MenuItem } from '@mui/material';
import {
  cloneElement,
  isValidElement,
  useId,
  useState,
  type MouseEvent,
  type ReactElement,
} from 'react';

import type { MenuProps } from './Menu.types';

const DEFAULT_WIDTH = 230;

export function Menu({ trigger, entries, width = DEFAULT_WIDTH }: MenuProps) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const open = anchor !== null;
  const menuId = useId();

  type TriggerProps = {
    onClick?: (e: MouseEvent<HTMLElement>) => void;
    'aria-haspopup'?: 'menu';
    'aria-expanded'?: boolean;
    'aria-controls'?: string;
  };

  const triggerEl = isValidElement(trigger)
    ? cloneElement(trigger as ReactElement<TriggerProps>, {
        'aria-haspopup': 'menu',
        'aria-expanded': open,
        ...(open ? { 'aria-controls': menuId } : {}),
        onClick: (event: MouseEvent<HTMLElement>) => {
          setAnchor(event.currentTarget);
          (trigger as ReactElement<TriggerProps>).props.onClick?.(event);
        },
      })
    : trigger;

  return (
    <>
      {triggerEl}
      <MuiMenu
        id={menuId}
        anchorEl={anchor}
        open={open}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{
          // `minWidth`, not `width`: the reference gives each popover a
          // width, but below `md` this same menu absorbs the app bar's
          // organization display and support panel (`AppBar`'s
          // `collapsedEntries`), and a hard width cuts the correlation id
          // and its Copy button off the right edge. A floor keeps the
          // desktop proportions and still lets the collapsed menu size to
          // what it is carrying — bounded by the viewport, so it can never
          // grow off-screen on a phone.
          paper: {
            sx: { minWidth: width, maxWidth: 'calc(100vw - 32px)', mt: 0.75, borderRadius: 2 },
          },
          list: { sx: { p: 0.75 } },
        }}
      >
        {entries.map((entry, index) => {
          if (entry.kind === 'caption') {
            return (
              <ListSubheader
                key={`caption-${String(index)}`}
                disableSticky
                sx={{
                  px: '11px',
                  pt: '9px',
                  pb: '5px',
                  lineHeight: 1.4,
                  fontSize: 10.5,
                  fontWeight: 700,
                  letterSpacing: '0.07em',
                  textTransform: 'uppercase',
                  color: 'text.secondary',
                  backgroundColor: 'transparent',
                }}
              >
                {entry.label}
              </ListSubheader>
            );
          }
          if (entry.kind === 'divider') {
            return <Divider key={`divider-${String(index)}`} sx={{ my: 0.5 }} />;
          }
          if (entry.kind === 'content') {
            return (
              <Box key={`content-${String(index)}`} sx={{ px: '11px', py: '8px' }}>
                {entry.content}
              </Box>
            );
          }
          return (
            <MenuItem
              key={entry.label}
              disabled={entry.disabled === true}
              selected={entry.selected === true}
              onClick={(event) => {
                entry.onClick?.(event);
                setAnchor(null);
              }}
              sx={{
                gap: '11px',
                px: '11px',
                py: '9px',
                minHeight: 36,
                borderRadius: 1.5,
                fontSize: 13.5,
                ...(entry.danger === true ? { color: 'error.main' } : {}),
                // The icon inherits the row's colour, so `danger` and
                // `selected` tint the glyph with the label rather than
                // leaving a grey icon beside red text.
                '& .MuiSvgIcon-root': { fontSize: 18, color: 'inherit' },
              }}
            >
              {entry.icon}
              <Box component="span" sx={{ flex: 1, minWidth: 0 }}>
                {entry.label}
              </Box>
              {entry.trailing}
            </MenuItem>
          );
        })}
      </MuiMenu>
    </>
  );
}
