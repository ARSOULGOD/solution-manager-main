/**
 * Searchable multi-selection over the users a group may contain (Groups
 * management §3/§5). Shared by the create form's "initial members" field
 * and the Add members dialog, so the two cannot disagree about who is
 * eligible.
 *
 * The eligibility rules are `groupValidation.selectableMembers`, not this
 * component: only ACTIVE users of the group's own organization, and never
 * a user who is already a member. Ineligible users are not rendered as
 * disabled rows — they are absent — because a control an operator can see
 * but never use is the puzzle-disable `FE-D9` exists to prevent.
 */
import { Box, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import type { LocalUser } from '@shared/testdoubles/data/rbac';
import { Alert, Checkbox, SearchField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

export interface MemberPickerProps {
  /** Already filtered to the eligible set by the caller. */
  candidates: readonly LocalUser[];
  selectedIds: readonly string[];
  onChange: (next: string[]) => void;
  /** Rendered instead of the list when there is nothing to choose from. */
  emptyMessage: string;
  disabled?: boolean;
  searchLabel?: string;
}

export function MemberPicker({
  candidates,
  selectedIds,
  onChange,
  emptyMessage,
  disabled = false,
  searchLabel,
}: MemberPickerProps): ReactElement {
  const [query, setQuery] = useState('');

  const visible = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    if (needle === '') return candidates;
    return candidates.filter(
      (user) =>
        user.name.toLocaleLowerCase().includes(needle) ||
        user.email.toLocaleLowerCase().includes(needle),
    );
  }, [candidates, query]);

  if (candidates.length === 0) {
    return <Alert severity="neutral">{emptyMessage}</Alert>;
  }

  const selected = new Set(selectedIds);

  function toggle(userId: string, checked: boolean): void {
    const next = new Set(selected);
    if (checked) next.add(userId);
    else next.delete(userId);
    onChange([...next]);
  }

  return (
    <Stack gap={1}>
      <SearchField
        label={searchLabel ?? t('adminGroupAddMembersSearchLabel')}
        value={query}
        onChange={setQuery}
      />
      <Box
        sx={{
          maxHeight: 220,
          overflowY: 'auto',
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          px: 1.5,
          py: 1,
        }}
      >
        {visible.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            {t('adminGroupsNoResultsTitle')}
          </Typography>
        ) : (
          visible.map((user) => (
            <Box key={user.id} sx={{ display: 'flex', alignItems: 'center', minWidth: 0 }}>
              <Checkbox
                label={`${user.name} · ${user.email}`}
                checked={selected.has(user.id)}
                disabled={disabled}
                onChange={(checked) => toggle(user.id, checked)}
              />
            </Box>
          ))
        )}
      </Box>
      <Typography variant="caption" color="text.secondary" aria-live="polite">
        {t('adminGroupAddMembersSelected', { count: selectedIds.length })}
      </Typography>
    </Stack>
  );
}
