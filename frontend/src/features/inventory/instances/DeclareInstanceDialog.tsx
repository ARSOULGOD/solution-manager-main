/**
 * Declare-instance form (task 115, superseding task 045's stub).
 *
 * **What changed and why the old comment is gone.** 045 refused to render
 * a form because §16 Q10 defines no API, no field set and no entitlement
 * check for declaring an instance. Q10 is still open. What changed is the
 * decision, not the spec: the console now asks for the identity fields an
 * operator can know — the same ones the instance grid displays and
 * `derivePackageCatalog.ts` otherwise guesses on their behalf — and says
 * plainly, in the dialog, that nothing yet backs them.
 *
 * **The line this form does not cross.** It asks for identity only. Every
 * observed fact — health, config state, `version.observed`, `lastSeen` —
 * is absent by design and derived as "not yet observed" server-side. A
 * declare form that let you type a health status would be inventing
 * observations, which is a different and much worse thing than inventing
 * a field set.
 *
 * Configuration is NOT collected here. The package's own fields belong to
 * the Designer, which renders them from the package via `project()`;
 * duplicating them here would make the same field editable in two places
 * with no rule for which wins.
 *
 * **Org / Sub-org / Environment is a dependent cascade** (`FE-D46`), not
 * three independent fields. Each level loads from `HierarchyRepository`
 * only once the level above it is chosen, and changing a level clears
 * every level below it (`setOrg` / `setSubOrg` in `declareForm.ts`). The
 * controls below a level with no selection are DISABLED with a helper
 * line saying what to choose first — an enabled empty select invites the
 * operator to click something that cannot answer them.
 *
 * Errors surface on the first Declare press, then track live. The submit
 * button is never disabled by validation — a disabled control with no
 * stated reason is the failure mode `EmptyState`'s copy elsewhere calls
 * out; pressing it and being told what's wrong is the accessible path.
 */
import { Box, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { queryKeys } from '@shared/api/queryKeys';
import type { HierarchyRepository } from '@shared/hierarchy';
import { t } from '@shared/i18n';
import type { InstanceRow } from '@shared/testdoubles/data/instances';
import { Alert, Button, Dialog, Select, TextField, ToggleGroup } from '@shared/ui';
import { Grid, Stack } from '@shared/ui/layout';
import { driverVocab, locusVocab, type DriverKey, type LocusKey } from '@shared/vocab';

import {
  addReplica,
  hasErrors,
  initialDeclareForm,
  removeReplica,
  renameInstance,
  setOrg,
  setReplica,
  setSubOrg,
  toDeclareInput,
  validateDeclareForm,
  type DeclareFormErrors,
  type DeclareFormState,
} from './declareForm';
import type { DeclareInstanceInput } from './instancesRepository';

export interface DeclareInstanceDialogProps {
  open: boolean;
  solutionId: string;
  /** Instances already declared for this solution — the name default and the uniqueness check. */
  existing: readonly InstanceRow[];
  /**
   * The versions this solution's package serves, ascending. The Package
   * version options are built from THIS — never from a list written into
   * this file, which would be per-solution console code of exactly the
   * kind `REQ-001` forbids. A single-entry list is preselected; two or
   * more require a choice.
   */
  packageVersions: readonly (string | number)[];
  /** Backs the Organization → Sub-org → Environment cascade (`FE-D46`). */
  hierarchyRepository: HierarchyRepository;
  onClose: () => void;
  onSubmit: (input: DeclareInstanceInput) => void;
  /** The local demo uses this existing declaration flow for one SSH Docker target. */
  sshDockerEnabled?: boolean;
  discoverHostKey?: (host: string, port: number) => Promise<string>;
  submitting?: boolean;
  /**
   * A failed declare, rendered in place rather than only as a toast —
   * design §16: an error the operator must act on does not get delivered
   * as something that auto-dismisses in six seconds.
   */
  errorMessage?: string;
}

const DRIVER_OPTIONS = Object.values(driverVocab).map((entry) => ({
  value: entry.key,
  label: entry.label,
}));

const LOCUS_OPTIONS = Object.values(locusVocab).map((entry) => ({
  value: entry.key,
  label: entry.label,
}));

export function DeclareInstanceDialog({
  open,
  solutionId,
  existing,
  packageVersions,
  hierarchyRepository,
  onClose,
  onSubmit,
  sshDockerEnabled = false,
  discoverHostKey,
  submitting = false,
  errorMessage,
}: DeclareInstanceDialogProps) {
  // Initialised once per mount. The page mounts this only while open (see
  // `InstancesPage`), so "per mount" is "per opening" — a cancelled draft
  // is never waiting for the operator the next time they press Declare.
  const [state, setState] = useState<DeclareFormState>(() =>
    initialDeclareForm(solutionId, existing, packageVersions),
  );
  const [showErrors, setShowErrors] = useState(false);
  const [ssh, setSsh] = useState({
    host: '',
    port: '22',
    user: '',
    fingerprint: '',
    containerName: '',
    remoteConfigDirectory: '',
    containerConfigPath: '',
    configFileName: '',
  });
  const [keyFile, setKeyFile] = useState<File | null>(null);
  const [sshError, setSshError] = useState<string | undefined>();
  const [discoveringHostKey, setDiscoveringHostKey] = useState(false);

  // The cascade, one query per level (`FE-D46`). Each is `enabled` only
  // once the level above it has a value, so a sub-org list is never
  // fetched for "no organization" and the loads happen in the order the
  // operator makes the choices.
  const organizationsQuery = useQuery({
    queryKey: queryKeys.hierarchy.organizations(),
    queryFn: ({ signal }) => hierarchyRepository.organizations(signal),
    enabled: open,
  });
  const subOrgsQuery = useQuery({
    queryKey: queryKeys.hierarchy.subOrgs(state.org),
    queryFn: ({ signal }) => hierarchyRepository.subOrgs(state.org, signal),
    enabled: open && state.org !== '',
  });
  const environmentsQuery = useQuery({
    queryKey: queryKeys.hierarchy.environments(state.org, state.subOrg),
    queryFn: ({ signal }) => hierarchyRepository.environments(state.org, state.subOrg, signal),
    enabled: open && state.org !== '' && state.subOrg !== '',
  });

  const toOptions = (values: readonly string[]): { value: string; label: string }[] =>
    values.map((value) => ({ value, label: value }));

  const organizationOptions = toOptions(organizationsQuery.data ?? []);
  const subOrgOptions = toOptions(subOrgsQuery.data ?? []);
  const environmentOptions = (environmentsQuery.data ?? []).map((env) => ({
    value: env,
    label: env,
  }));

  /**
   * The helper line under one cascade level.
   *
   * A FAILED load and an EMPTY one are different facts and must not read
   * the same. "This organization has no sub-orgs" is a statement about the
   * data; a network error is a statement about the request, and an
   * operator shown the former when the latter happened goes looking for
   * the wrong problem. `gatedText` covers the third case — the level above
   * has not been answered yet, so there is nothing to have loaded.
   */
  const levelHelp = (
    query: { isError: boolean; isFetching: boolean },
    ready: boolean,
    gatedText: string,
  ): string | undefined => {
    if (!ready) return gatedText === '' ? undefined : gatedText;
    if (query.isError) return t('instancesDeclareHierarchyError');
    if (query.isFetching) return t('instancesDeclareHierarchyLoading');
    return undefined;
  };

  const takenNames = existing.map((row) => row.name);
  const errors = validateDeclareForm(
    state,
    takenNames,
    {
      required: t('instancesDeclareErrRequired'),
      nameFormat: t('instancesDeclareErrNameFormat'),
      nameTaken: t('instancesDeclareErrNameTaken'),
      replicaDuplicate: t('instancesDeclareErrReplicaDuplicate'),
      packageVersionUnknown: t('instancesDeclareErrPackageVersion'),
    },
    packageVersions,
  );

  // Built from the prop, never a literal list — see `packageVersions`.
  const packageVersionOptions = packageVersions.map((version) => ({
    value: String(version),
    label: String(version),
  }));
  const visible: DeclareFormErrors = showErrors ? errors : {};

  // After `visible`: a validation error OUTRANKS the gated/loading line.
  // Both occupy the one helper slot each control has, and "Sub-org is
  // required" is the more actionable of the two.
  const orgHelp = visible.org ?? levelHelp(organizationsQuery, true, '');
  const subOrgHelp =
    visible.subOrg ?? levelHelp(subOrgsQuery, state.org !== '', t('instancesDeclareSubOrgGated'));
  const envHelp =
    visible.env ?? levelHelp(environmentsQuery, state.subOrg !== '', t('instancesDeclareEnvGated'));

  function handleSubmit(): void {
    setShowErrors(true);
    if (hasErrors(errors)) {
      return;
    }
    let input = toDeclareInput(state, packageVersions);
    if (sshDockerEnabled) {
      const port = Number(ssh.port);
      if (
        state.driver !== 'docker' ||
        state.locus !== 'dp_remote' ||
        ssh.host.trim() === '' ||
        !Number.isInteger(port) ||
        port < 1 ||
        port > 65535 ||
        ssh.user.trim() === '' ||
        keyFile === null ||
        ssh.fingerprint.trim() === '' ||
        ssh.containerName.trim() === '' ||
        ssh.remoteConfigDirectory.trim() === '' ||
        ssh.containerConfigPath.trim() === '' ||
        ssh.configFileName.trim() === ''
      ) {
        setSshError(t('instancesDeclareSshRequired'));
        return;
      }
      const containerName = ssh.containerName.trim();
      input = {
        ...input,
        // The SSH Docker demo is deliberately a single-container target.
        // The generic inventory replica editor models fan-out, which this
        // transport neither exposes nor admits.
        replicas: [containerName],
        sshDocker: {
          host: ssh.host.trim(),
          port,
          user: ssh.user.trim(),
          keyFile,
          pinnedHostKeyFingerprint: ssh.fingerprint.trim(),
          containerName,
          remoteConfigDirectory: ssh.remoteConfigDirectory.trim(),
          containerConfigPath: ssh.containerConfigPath.trim(),
          configFileName: ssh.configFileName.trim(),
        },
      };
    }
    onSubmit(input);
  }

  async function handleDiscoverHostKey(): Promise<void> {
    const port = Number(ssh.port);
    if (discoverHostKey === undefined || ssh.host.trim() === '' || !Number.isInteger(port) || port < 1 || port > 65535) {
      setSshError(t('instancesDeclareSshHostRequired'));
      return;
    }
    setSshError(undefined);
    setDiscoveringHostKey(true);
    try {
      const fingerprint = await discoverHostKey(ssh.host.trim(), port);
      setSsh((current) => ({ ...current, fingerprint }));
    } catch {
      setSshError(t('instancesDeclareSshDiscoverFailed'));
    } finally {
      setDiscoveringHostKey(false);
    }
  }

  // The chosen locus's own tip states its credential posture — the one
  // thing about this choice an operator has to get right, so it is shown
  // inline rather than hidden behind a hover.
  const locusHelp = locusVocab[state.locus].tip;

  return (
    <Dialog
      open={open}
      title={t('instancesDeclareDialogTitle')}
      onClose={onClose}
      dirty
      // `md`, not the `sm` default: `Grid`'s columns follow the CONTAINER's
      // width, and a 600px dialog never reaches the `sm` breakpoint, so the
      // paired fields below would stack no matter what they declare.
      maxWidth="md"
      fullScreenBelow="sm"
      actions={
        <>
          <Button variant="text" onClick={onClose} disabled={submitting}>
            {t('cancel')}
          </Button>
          <Button onClick={handleSubmit} disabled={submitting}>
            {submitting ? t('instancesDeclareSubmitting') : t('instancesDeclareSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2.5}>
        <Typography variant="body2">{t('instancesDeclareDialogIntro')}</Typography>
        <Alert severity="info">
          {sshDockerEnabled ? t('instancesDeclareSshDemoNote') : t('instancesDeclareDevNote')}
        </Alert>

        {errorMessage !== undefined && (
          <Alert severity="err" title={t('instancesDeclareFailedTitle')}>
            {errorMessage}
          </Alert>
        )}

        <TextField
          label={t('instancesDeclareName')}
          required
          autoFocus
          value={state.name}
          onChange={(event) => setState((prev) => renameInstance(prev, event.target.value))}
          {...(visible.name !== undefined
            ? { error: visible.name }
            : { helperText: t('instancesDeclareNameHelp') })}
        />

        {/* Directly under Name: it is IDENTITY, and it decides which
            fields the Designer will render for this instance. Distinct
            from `Declared version` at the bottom of the form, which is
            the MI build — the two helper texts say so explicitly, because
            two fields called "version" on one form need the difference
            stated rather than inferred. */}
        <Select
          label={t('instancesDeclarePackageVersion')}
          required
          value={state.packageVersion}
          onChange={(packageVersion) => setState((prev) => ({ ...prev, packageVersion }))}
          options={packageVersionOptions}
          {...(visible.packageVersion !== undefined
            ? { error: visible.packageVersion }
            : { helperText: t('instancesDeclarePackageVersionHelp') })}
        />

        {/* The cascade, in the order it must be answered (`FE-D46`):
            Organization → Sub-org → Environment. Environment used to sit
            ABOVE these two as a free toggle over a hardcoded DEV/TEST/PROD;
            it now depends on both and so has to follow them on screen. */}
        <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
          <Select
            label={t('instancesDeclareOrg')}
            required
            value={state.org}
            onChange={(org) => setState((prev) => setOrg(prev, org))}
            options={organizationOptions}
            {...(visible.org !== undefined
              ? { error: visible.org }
              : orgHelp !== undefined
                ? { helperText: orgHelp }
                : {})}
          />
          <Select
            label={t('instancesDeclareSubOrg')}
            required
            value={state.subOrg}
            onChange={(subOrg) => setState((prev) => setSubOrg(prev, subOrg))}
            options={subOrgOptions}
            disabled={state.org === ''}
            {...(visible.subOrg !== undefined
              ? { error: visible.subOrg }
              : subOrgHelp !== undefined
                ? { helperText: subOrgHelp }
                : {})}
          />
        </Grid>

        <Box>
          <Typography variant="body2" component="div" sx={{ mb: 1 }}>
            {t('instancesDeclareEnv')}
          </Typography>
          <ToggleGroup
            aria-label={t('instancesDeclareEnv')}
            value={state.env}
            onChange={(env) => setState((prev) => ({ ...prev, env }))}
            options={environmentOptions}
            disabled={state.subOrg === ''}
          />
          {/* `ToggleGroup` has no helperText slot of its own, so the
              gated / loading / failed / invalid line is rendered here. */}
          {envHelp !== undefined && (
            <Typography
              variant="caption"
              component="div"
              sx={{ mt: 0.5 }}
              color={visible.env !== undefined ? 'error.main' : 'text.secondary'}
            >
              {envHelp}
            </Typography>
          )}
        </Box>

        <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
          <Select
            label={t('instancesDeclareDriver')}
            value={state.driver}
            onChange={(driver) => setState((prev) => ({ ...prev, driver: driver as DriverKey }))}
            options={DRIVER_OPTIONS}
            disabled={sshDockerEnabled}
          />
          <Select
            label={t('instancesDeclareLocus')}
            value={state.locus}
            onChange={(locus) => setState((prev) => ({ ...prev, locus: locus as LocusKey }))}
            options={LOCUS_OPTIONS}
            disabled={sshDockerEnabled}
            {...(locusHelp !== undefined ? { helperText: locusHelp } : {})}
          />
        </Grid>

        {sshDockerEnabled && (
          <Box>
            <Typography variant="body2" component="div" sx={{ mb: 1 }}>
              {t('instancesDeclareSshTitle')}
            </Typography>
            <Stack gap={1.5}>
              <Alert severity="info">{t('instancesDeclareSshIntro')}</Alert>
              {sshError !== undefined && <Alert severity="err">{sshError}</Alert>}
              <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
                <TextField
                  label={t('instancesDeclareSshHost')}
                  required
                  value={ssh.host}
                  onChange={(event) => setSsh((current) => ({ ...current, host: event.target.value }))}
                />
                <TextField
                  label={t('instancesDeclareSshPort')}
                  required
                  value={ssh.port}
                  onChange={(event) => setSsh((current) => ({ ...current, port: event.target.value }))}
                />
              </Grid>
              <TextField
                label={t('instancesDeclareSshUser')}
                required
                value={ssh.user}
                onChange={(event) => setSsh((current) => ({ ...current, user: event.target.value }))}
              />
              <Stack gap={1.5}>
                <Button
                  variant="outlined"
                  size="sm"
                  disabled={discoveringHostKey}
                  onClick={() => void handleDiscoverHostKey()}
                >
                  {discoveringHostKey ? t('instancesDeclareSshDiscovering') : t('instancesDeclareSshDiscover')}
                </Button>
                <TextField
                  label={t('instancesDeclareSshFingerprint')}
                  required
                  value={ssh.fingerprint}
                  readOnly
                  onChange={() => undefined}
                  helperText={t('instancesDeclareSshFingerprintHelp')}
                />
              </Stack>
              <Box>
                <Typography variant="body2" component="label" htmlFor="ssh-private-key">
                  {t('instancesDeclareSshKey')}
                </Typography>
                <input
                  id="ssh-private-key"
                  type="file"
                  accept=".pem,.key,application/x-pem-file"
                  onChange={(event) => setKeyFile(event.target.files?.[0] ?? null)}
                />
                <Typography variant="caption" component="div" color="text.secondary">
                  {keyFile === null ? t('instancesDeclareSshKeyHelp') : keyFile.name}
                </Typography>
              </Box>
              <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
                <TextField
                  label={t('instancesDeclareSshContainer')}
                  required
                  value={ssh.containerName}
                  onChange={(event) => setSsh((current) => ({ ...current, containerName: event.target.value }))}
                />
                <TextField
                  label={t('instancesDeclareSshConfigFile')}
                  required
                  value={ssh.configFileName}
                  onChange={(event) => setSsh((current) => ({ ...current, configFileName: event.target.value }))}
                />
              </Grid>
              <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
                <TextField
                  label={t('instancesDeclareSshHostConfigDirectory')}
                  required
                  value={ssh.remoteConfigDirectory}
                  onChange={(event) => setSsh((current) => ({ ...current, remoteConfigDirectory: event.target.value }))}
                />
                <TextField
                  label={t('instancesDeclareSshContainerConfigDirectory')}
                  required
                  value={ssh.containerConfigPath}
                  onChange={(event) => setSsh((current) => ({ ...current, containerConfigPath: event.target.value }))}
                />
              </Grid>
            </Stack>
          </Box>
        )}

        {!sshDockerEnabled && (
          <Box>
            <Typography variant="body2" component="div" sx={{ mb: 1 }}>
              {t('instancesDeclareReplicas')}
            </Typography>
            <Stack gap={1.5}>
              {state.replicas.map((replica, index) => {
                const replicaError = visible.replicas?.[index];
                const position = index + 1;
                return (
                  <Box
                    // Index-keyed deliberately: replica names are the values
                    // being edited, so they are not stable identities.
                    key={index}
                    sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}
                  >
                    <Box sx={{ flex: '1 1 auto', minWidth: 0 }}>
                      <TextField
                        label={t('instancesDeclareReplicaLabel', { position })}
                        required
                        value={replica}
                        onChange={(event) =>
                          setState((prev) => setReplica(prev, index, event.target.value))
                        }
                        {...(replicaError !== undefined ? { error: replicaError } : {})}
                      />
                    </Box>
                    {state.replicas.length > 1 && (
                      <Button
                        variant="text"
                        size="sm"
                        onClick={() => setState((prev) => removeReplica(prev, index))}
                      >
                        {t('instancesDeclareReplicaRemove', { position })}
                      </Button>
                    )}
                  </Box>
                );
              })}
              <Box>
                <Button variant="outlined" size="sm" onClick={() => setState(addReplica)}>
                  {t('instancesDeclareReplicaAdd')}
                </Button>
              </Box>
            </Stack>
          </Box>
        )}

        <TextField
          label={t('instancesDeclareVersion')}
          value={state.declaredVersion}
          onChange={(event) =>
            setState((prev) => ({ ...prev, declaredVersion: event.target.value }))
          }
          helperText={t('instancesDeclareVersionHelp')}
        />
      </Stack>
    </Dialog>
  );
}
