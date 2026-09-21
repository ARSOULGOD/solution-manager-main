/**
 * Selectable scope hierarchy (task 092, "Assignments and ScopeTree", UI
 * §A.12). Picking a node yields its full path below the synthetic root;
 * a grant made there reaches everything below it (`isScopePrefix`). Used by
 * the by-scope assignments view and the New assignment dialog, and reused
 * by the access check (FE-093).
 *
 * **The root's path is `[]`, not `[root.name]`** (`D20`, `FE-D46`). The tree is
 * rooted at a synthetic "All organizations" node that is not itself an
 * organization; selecting it means "every scope", which the prefix algebra
 * already spells as the empty path. So a branch is GIVEN its own path
 * rather than computing it by appending its name to its parent's — the
 * root is the one node whose name is not a path segment.
 *
 * The expand/collapse control is a sibling button, never nested inside
 * the node's own button — nested buttons are invalid and break the
 * accessibility tree.
 */
import { Box, Typography } from '@mui/material';
import { useState } from 'react';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import type { ScopeNode } from '@shared/testdoubles/data/rbac';

import { scopePathsEqual } from './scopePath';

export interface ScopeTreeProps {
  node: ScopeNode;
  selected: readonly string[];
  onSelect: (path: string[]) => void;
}

function ScopeBranch({
  node,
  here,
  selected,
  onSelect,
}: {
  node: ScopeNode;
  /** This node's OWN full scope path. `[]` for the synthetic root. */
  here: readonly string[];
  selected: readonly string[];
  onSelect: (path: string[]) => void;
}): ReactElement {
  const [open, setOpen] = useState(true);
  const hasChildren = node.children !== undefined && node.children.length > 0;
  const isSelected = scopePathsEqual(here, selected);

  return (
    <Box component="li" sx={{ listStyle: 'none' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
        {hasChildren ? (
          <Box
            component="button"
            type="button"
            onClick={() => setOpen((current) => !current)}
            aria-label={
              open
                ? t('adminScopeTreeCollapse', { name: node.name })
                : t('adminScopeTreeExpand', { name: node.name })
            }
            aria-expanded={open}
            sx={{
              all: 'unset',
              cursor: 'pointer',
              px: 0.5,
              lineHeight: 1,
              color: 'text.secondary',
              '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
            }}
          >
            {open ? '▾' : '▸'}
          </Box>
        ) : (
          <Box sx={{ width: 20 }} />
        )}
        <Box
          component="button"
          type="button"
          onClick={() => onSelect([...here])}
          aria-label={node.name}
          aria-pressed={isSelected}
          sx={{
            all: 'unset',
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1,
            cursor: 'pointer',
            borderRadius: 1,
            px: 1,
            py: 0.5,
            boxSizing: 'border-box',
            bgcolor: isSelected ? 'action.selected' : 'transparent',
            '&:hover': { bgcolor: 'action.hover' },
            '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
          }}
        >
          <Typography component="span" variant="body2">
            {node.name}
          </Typography>
          <Typography component="span" variant="caption" color="text.secondary">
            {node.level}
          </Typography>
        </Box>
      </Box>
      {hasChildren && open && (
        <Box component="ul" sx={{ pl: 2.5, m: 0 }}>
          {node.children?.map((child) => (
            <ScopeBranch
              key={child.name}
              node={child}
              here={[...here, child.name]}
              selected={selected}
              onSelect={onSelect}
            />
          ))}
        </Box>
      )}
    </Box>
  );
}

export function ScopeTree({ node, selected, onSelect }: ScopeTreeProps): ReactElement {
  return (
    <Box component="ul" sx={{ p: 0, m: 0 }}>
      <ScopeBranch node={node} here={[]} selected={selected} onSelect={onSelect} />
    </Box>
  );
}
