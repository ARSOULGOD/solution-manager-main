/**
 * Add a local user (task 094, `D13` 🔒 deferred, `FE-D38`, §16 Q5). Two
 * fields plus a credential path — and a standing note that creating a
 * user does **not** assign a role, because identity and authorization are
 * separate screens on purpose (an IdP migration then touches identity
 * only). `FE-D38` picks the admin-set temporary credential path; the
 * other two render so the choice is visible, `invite` disabled because
 * this console has no email capability. Fixture-backed — `AdminRepository`
 * is `CONTRACT: TBD` (§16 Q5).
 */
import { Box, FormControl, FormLabel, Radio, RadioGroup, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';
import type { ReactElement } from 'react';

import { queryKeys } from '@shared/api/queryKeys';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Dialog, Select, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';
import { pushOutcome } from '@shared/ui/Toaster';

import type { AdminRepository, CredentialPath } from '../adminRepository';

interface CredentialOption {
  value: CredentialPath;
  label: string;
  description: string;
  badge?: { label: string; tone: 'ok' | 'warn' };
  disabled?: boolean;
}

const CREDENTIAL_OPTIONS: readonly CredentialOption[] = [
  {
    value: 'temp',
    label: t('adminAddUserCredTempLabel'),
    description: t('adminAddUserCredTempDesc'),
    badge: { label: t('adminAddUserCredTempBadge'), tone: 'ok' },
  },
  {
    value: 'invite',
    label: t('adminAddUserCredInviteLabel'),
    description: t('adminAddUserCredInviteDesc'),
    badge: { label: t('adminAddUserCredInviteBadge'), tone: 'warn' },
    disabled: true,
  },
  {
    value: 'oob',
    label: t('adminAddUserCredOobLabel'),
    description: t('adminAddUserCredOobDesc'),
  },
];

const CREATED_DETAIL: Record<CredentialPath, string> = {
  temp: t('adminAddUserCreatedDetailTemp'),
  invite: t('adminAddUserCreatedDetailInvite'),
  oob: t('adminAddUserCreatedDetailOob'),
};

export interface AddUserDialogProps {
  repository: AdminRepository;
  onClose: () => void;
}

export function AddUserDialog({ repository, onClose }: AddUserDialogProps): ReactElement {
  const queryClient = useQueryClient();
  const legendId = useId();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [organization, setOrganization] = useState('');
  const [credentialPath, setCredentialPath] = useState<CredentialPath>('temp');

  // An account belongs to one ORGANIZATION — the top of the hierarchy
  // (`FE-D46`), and the constraint that decides which groups it may join.
  // The options are the scope tree's own top level, never a second list.
  const scopeTreeQuery = useQuery({
    queryKey: queryKeys.admin.scopeTree(),
    queryFn: ({ signal }) => repository.scopeTree(signal),
  });
  const organizations = (scopeTreeQuery.data?.children ?? []).map((child) => child.name);

  const create = useMutation({
    mutationFn: () => repository.createUser({ name, email, organization, credentialPath }),
    onSuccess: ({ user, correlationId }) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.users() });
      pushOutcome(`user:${user.id}`, {
        title: t('adminAddUserCreatedToast', { name: user.name }),
        detail: CREATED_DETAIL[credentialPath],
        correlationId,
        severity: 'ok',
      });
      onClose();
    },
  });

  const canSubmit =
    name.trim() !== '' && email.trim() !== '' && organization !== '' && !create.isPending;

  return (
    <Dialog
      open
      title={t('adminAddUserTitle')}
      onClose={onClose}
      dirty={name !== '' || email !== '' || organization !== ''}
      actions={
        <>
          <Button variant="text" onClick={onClose}>
            {t('cancel')}
          </Button>
          <Button onClick={() => create.mutate()} disabled={!canSubmit}>
            {create.isPending ? t('adminAddUserSubmitPending') : t('adminAddUserSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        <TextField
          label={t('adminAddUserNameLabel')}
          required
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
        <TextField
          label={t('adminAddUserEmailLabel')}
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <Select
          label={t('adminGroupOrganizationLabel')}
          required
          value={organization}
          onChange={setOrganization}
          disabled={scopeTreeQuery.isPending}
          helperText={t('adminAddUserOrganizationHint')}
          options={organizations.map((org) => ({ value: org, label: org }))}
        />

        <Alert severity="neutral">{t('adminAddUserNoRoleNote')}</Alert>

        <FormControl component="fieldset" fullWidth>
          <FormLabel id={legendId} component="legend" sx={{ mb: 1 }}>
            {t('adminAddUserCredentialHeading')}
          </FormLabel>
          <RadioGroup
            aria-labelledby={legendId}
            value={credentialPath}
            onChange={(event) => setCredentialPath(event.target.value as CredentialPath)}
          >
            {CREDENTIAL_OPTIONS.map((option) => {
              const selected = credentialPath === option.value;
              return (
                <Box
                  key={option.value}
                  sx={{
                    display: 'flex',
                    alignItems: 'flex-start',
                    gap: 1,
                    mt: 1,
                    p: 1.5,
                    border: 1,
                    borderRadius: 1,
                    borderColor: selected ? 'primary.main' : 'divider',
                    ...(selected ? { backgroundColor: 'action.selected' } : {}),
                    ...(option.disabled === true ? { opacity: 0.7 } : {}),
                  }}
                >
                  <Radio
                    value={option.value}
                    disabled={option.disabled ?? false}
                    slotProps={{ input: { 'aria-label': option.label } }}
                    sx={{ p: 0, mt: 0.25 }}
                  />
                  <Box sx={{ minWidth: 0 }}>
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1,
                        flexWrap: 'wrap',
                        mb: 0.25,
                      }}
                    >
                      <Typography component="span" variant="body2" sx={{ fontWeight: 600 }}>
                        {option.label}
                      </Typography>
                      {option.badge !== undefined && (
                        <Chip tone={option.badge.tone} label={option.badge.label} />
                      )}
                    </Box>
                    <Typography variant="body2" color="text.secondary">
                      {option.description}
                    </Typography>
                  </Box>
                </Box>
              );
            })}
          </RadioGroup>
        </FormControl>

        {create.isError && <Alert severity="err">{t('adminAddUserError')}</Alert>}
      </Stack>
    </Dialog>
  );
}
