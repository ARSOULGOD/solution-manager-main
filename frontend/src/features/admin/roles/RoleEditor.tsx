/**
 * Inspect / edit / clone / create a role (task 091, `D10` 🔒, UI §A.12).
 * Built-in roles are read-only but fully inspectable and cloneable —
 * "the built-in one but without restart" is the common real request, and
 * `RBAC_ROLES` models it (`payments-op`). The permission list is grouped by
 * resource, marks high-consequence and two-sided verbs, and renders every
 * verb's catalog status through `CatalogStatusChip`. Adding a verb is a
 * catalog data change (§16 Q4) — this layout does not move.
 */
import { Box, Typography } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import type { ReactElement } from 'react';

import { queryKeys } from '@shared/api/queryKeys';
import { PERMISSION_GROUPS } from '@shared/authz/catalog';
import { t } from '@shared/i18n';
import { Alert, Button, Checkbox, Chip, Dialog, TextArea, TextField, ToneIcon } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import { CatalogStatusChip } from './CatalogStatusChip';
import type { RoleEditorProps } from './RoleEditor.types';
import { expandRolePermissions, PERMISSION_BY_ID } from './rolePermissions';

const TITLE_KEY = {
  inspect: 'adminRoleEditorTitleInspect',
  edit: 'adminRoleEditorTitleEdit',
  clone: 'adminRoleEditorTitleClone',
  create: 'adminRoleEditorTitleCreate',
} as const;

export function RoleEditor({
  mode,
  role,
  repository,
  onClose,
  onClone,
}: RoleEditorProps): ReactElement {
  const readOnly = mode === 'inspect';
  const queryClient = useQueryClient();

  const initialSelected = useMemo(
    () => new Set(role ? expandRolePermissions(role.permissionIds) : []),
    [role],
  );
  const [selected, setSelected] = useState<ReadonlySet<string>>(initialSelected);
  const [name, setName] = useState(
    role === null
      ? ''
      : mode === 'clone'
        ? t('adminRoleCloneNameSuffix', { name: role.name })
        : role.name,
  );
  const [description, setDescription] = useState(role?.description ?? '');

  const save = useMutation({
    mutationFn: () =>
      repository.saveRole({
        ...(mode === 'edit' && role !== null ? { id: role.id } : {}),
        name: name.trim(),
        description: description.trim(),
        permissionIds: [...selected],
      }),
    onSuccess: ({ role: saved, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.roles() });
      pushOutcome(`role:${saved.id}`, {
        title: t('adminRoleSavedToast', { name: saved.name }),
        detail: t('adminRoleSavedToastDetail', { count: [...selected].length }),
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  function toggle(id: string): void {
    if (readOnly) return;
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function setGroup(ids: readonly string[], on: boolean): void {
    if (readOnly) return;
    setSelected((current) => {
      const next = new Set(current);
      for (const id of ids) {
        if (on) {
          next.add(id);
        } else {
          next.delete(id);
        }
      }
      return next;
    });
  }

  const canSave = name.trim().length > 0 && !save.isPending;
  const dirty = !readOnly && (save.isPending || name.trim().length > 0);

  const actions = readOnly ? (
    <>
      <Button variant="text" onClick={onClose}>
        {t('close')}
      </Button>
      <Button onClick={onClone}>{t('adminRoleEditorCloneAction')}</Button>
    </>
  ) : (
    <>
      <Button variant="text" onClick={onClose} disabled={save.isPending}>
        {t('cancel')}
      </Button>
      <Button onClick={() => save.mutate()} disabled={!canSave}>
        {save.isPending ? t('adminRoleEditorSavePending') : t('adminRoleEditorSaveAction')}
      </Button>
    </>
  );

  return (
    <Dialog
      open
      title={t(TITLE_KEY[mode])}
      maxWidth="md"
      onClose={onClose}
      dirty={dirty}
      actions={actions}
    >
      <Stack gap={2}>
        {readOnly ? (
          <Alert severity="info" title={t('adminRoleEditorBuiltInTitle')} fullWidth>
            {t('adminRoleEditorBuiltInBody')}
          </Alert>
        ) : (
          <>
            <TextField
              label={t('adminRoleEditorNameLabel')}
              required
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoFocus
              fullWidth
            />
            <TextArea
              label={t('adminRoleEditorDescriptionLabel')}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              minRows={2}
              fullWidth
            />
          </>
        )}

        {role?.clonedFrom !== undefined && (
          <Typography variant="caption" color="text.secondary">
            {t('adminRoleEditorClonedFrom', { source: role.clonedFrom })}
          </Typography>
        )}

        <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
          <Typography variant="overline">{t('adminRoleEditorPermissionsHeading')}</Typography>
          <Typography variant="caption" color="text.secondary">
            {t('adminRoleEditorSelectedCount', { count: selected.size })}
          </Typography>
        </Box>

        {PERMISSION_GROUPS.map((group) => {
          const allOn = group.permissionIds.every((id) => selected.has(id));
          return (
            <Box
              key={group.resource}
              component="fieldset"
              sx={{ border: 0, m: 0, p: 0, minWidth: 0 }}
            >
              <Box
                component="legend"
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  width: '100%',
                  p: 0,
                  mb: 0.5,
                }}
              >
                <Typography variant="subtitle2" component="span">
                  {group.label}
                </Typography>
                {!readOnly && (
                  <Button
                    variant="text"
                    size="sm"
                    onClick={() => setGroup(group.permissionIds, !allOn)}
                  >
                    {allOn ? t('adminRoleEditorGroupClear') : t('adminRoleEditorGroupAll')}
                  </Button>
                )}
              </Box>
              <Stack gap={0.5}>
                {group.permissionIds.map((id) => {
                  const def = PERMISSION_BY_ID.get(id);
                  const status = def?.status ?? 'absent';
                  return (
                    <Box
                      key={id}
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1,
                        flexWrap: 'wrap',
                      }}
                    >
                      <Checkbox
                        label={id}
                        checked={selected.has(id)}
                        onChange={() => toggle(id)}
                        disabled={readOnly}
                      />
                      <Box sx={{ flex: 1 }} />
                      {def?.highConsequence === true && (
                        <Box
                          component="span"
                          sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25 }}
                        >
                          <ToneIcon tone="warn" fontSize="small" />
                          <Typography variant="caption" component="span">
                            {t('adminRoleHighConsequence')}
                          </Typography>
                        </Box>
                      )}
                      {def?.twoSided === true && (
                        <Chip tone="info" label={t('adminRoleTwoSided')} />
                      )}
                      <CatalogStatusChip status={status} />
                    </Box>
                  );
                })}
              </Stack>
            </Box>
          );
        })}

        <Alert severity="info" title={t('adminRoleCatalogFindingTitle')} fullWidth>
          {t('adminRoleCatalogFindingBody')}
        </Alert>

        {save.isError && (
          <Alert severity="err" fullWidth>
            {t('adminRoleEditorSaveError')}
          </Alert>
        )}
      </Stack>
    </Dialog>
  );
}
