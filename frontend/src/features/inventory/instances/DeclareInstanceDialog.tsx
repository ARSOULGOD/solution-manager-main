/**
 * Declare-instance form (task 115, superseding task 045's stub).
 *
 * Rewritten for integration plan 007 (2026-10-09, consolidated design
 * session item 1) against the real `declareInstance` contract. The
 * previous version's field set (`role`, `driver`, `locus`, a multi-replica
 * name array, promoted `connections`) was invented against an open §16
 * Q10 — none of it exists on the real request body. See `declareForm.ts`'s
 * header for the full account of what was dropped and why each piece
 * doesn't just move elsewhere on this form.
 *
 * **What this form asks for now, and why.** Identity (name, package
 * version) and placement: a deployment mode and its host binding, plus the
 * envelope the real body requires — `containerName`/`configDirectory`
 * inside the binding's own allowed roots, `containerConfigPath`,
 * `fileName`, `healthPort`, and the acquisition mode (`direct-http` |
 * `via-ssh`, M-Q2). Configuration is still NOT collected here — the
 * package's own fields belong to the Designer.
 *
 * **Integration plan 014: Connection, not Target.** The Deployment mode
 * dropdown lists all three members of the vocabulary; only `dp-remote` is
 * selectable (the other two are reserved — disabled, with helper copy
 * saying so). For `dp-remote` the binding is an active `remote-server`
 * Connection (`ConnectionPickerRepository`), which REPLACES plan 007's
 * target picker on this form — the two are never shown together, and the
 * submitted body carries `connectionId`, never `targetId`.
 *
 * **Org / Sub-org / Environment is a dependent cascade** (`FE-D46`), not
 * three independent fields. Each level loads from `HierarchyRepository`
 * only once the level above it is chosen, and changing a level clears
 * every level below it, including the connection (`setOrg` / `setSubOrg`
 * / `setEnv` in `declareForm.ts`).
 *
 * Errors surface on the first Declare press, then track live. The submit
 * button is never disabled by validation — a disabled control with no
 * stated reason is the failure mode `EmptyState`'s copy elsewhere calls
 * out; pressing it and being told what's wrong is the accessible path.
 */
import { Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import type {
  AcquisitionMode,
  DeploymentMode,
  Instance,
} from '@shared/api/contracts/instances';
import { queryKeys } from '@shared/api/queryKeys';
import type { HierarchyRepository } from '@shared/hierarchy';
import { t } from '@shared/i18n';
import { Alert, Button, Dialog, Select, TextField } from '@shared/ui';
import { Grid, Stack } from '@shared/ui/layout';

import { deriveComponentRoleGroups } from './componentRoles';
import type { ConnectionPickerRepository } from './connectionPickerRepository';
import {
  DEPLOYMENT_MODES,
  initialDeclareForm,
  hasErrors,
  setDeploymentMode,
  setEnv,
  setOrg,
  setPackageVersion as applyPackageVersion,
  setSubOrg,
  setTarget,
  toDeclareInput,
  validateDeclareForm,
  type DeclareFormErrors,
  type DeclareFormState,
} from './declareForm';
import type { DeclareInstanceInput } from './instancesRepository';
import type { TargetsRepository } from './targetPickerRepository';

export interface DeclareInstanceDialogProps {
  open: boolean;
  solutionId: string;
  /** Instances already declared for this solution — the name default and the uniqueness check. */
  existing: readonly Instance[];
  /**
   * The versions this solution's package serves, ascending. The Package
   * version options are built from THIS — never from a list written into
   * this file, which would be per-solution console code of exactly the
   * kind `REQ-001` forbids. A single-entry list is preselected; two or
   * more require a choice.
   */
  packageVersions: readonly (string | number)[];
  /** Loads package metadata so role choices are derived from the selected version. */
  getPackage?: (solutionId: string, packageVersion?: string | number, signal?: AbortSignal) => Promise<import('@contracts/view-model/types').SolutionPackage>;
  /** Backs the Organization → Sub-org → Environment cascade (`FE-D46`) and the environment id `declareInstance`'s path needs. */
  hierarchyRepository: HierarchyRepository;
  /** Backs the `dp-remote` Connection picker (plan 014). */
  connectionsRepository: Pick<ConnectionPickerRepository, 'listRemoteServers'>;
  /** Optional new deployment-target source. When supplied, the form binds Instance → Target → Connection. */
  targetsRepository?: Pick<TargetsRepository, 'list'>;
  onClose: () => void;
  /** `environmentId` is resolved separately from `input` — the route is environment-addressed (`OR-52`), the body is not. */
  onSubmit: (environmentId: string, input: DeclareInstanceInput) => void;
  submitting?: boolean;
  /**
   * A failed declare, rendered in place rather than only as a toast —
   * design §16: an error the operator must act on does not get delivered
   * as something that auto-dismisses in six seconds.
   */
  errorMessage?: string;
}

const DEPLOYMENT_MODE_LABELS: Record<DeploymentMode, string> = {
  'dp-remote': t('instancesDeclareDeploymentModeDpRemote'),
  'host-local': t('instancesDeclareDeploymentModeHostLocal'),
  federated: t('instancesDeclareDeploymentModeFederated'),
};

// All three members are listed; the reserved two are `disabled` options,
// so they are visible but cannot be chosen (plan 014 compatibility rules).
const DEPLOYMENT_MODE_OPTIONS = DEPLOYMENT_MODES.map(({ value, selectable }) => ({
  value,
  label: DEPLOYMENT_MODE_LABELS[value],
  disabled: !selectable,
}));

const ACQUISITION_OPTIONS: { value: AcquisitionMode; label: string }[] = [
  { value: 'direct-http', label: t('instancesDeclareAcquisitionDirectHttp') },
  { value: 'via-ssh', label: t('instancesDeclareAcquisitionViaSsh') },
];

export function DeclareInstanceDialog({
  open,
  solutionId,
  existing,
  packageVersions,
  getPackage,
  hierarchyRepository,
  connectionsRepository,
  targetsRepository,
  onClose,
  onSubmit,
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
  const [submitError, setSubmitError] = useState<string | undefined>(undefined);
  const selectedPackageVersion = packageVersions.find((version) => String(version) === state.packageVersion) ?? state.packageVersion;
  const packageQuery = useQuery({
    queryKey: ['solutions', 'package', solutionId, selectedPackageVersion],
    queryFn: ({ signal }) => getPackage!(solutionId, selectedPackageVersion, signal),
    enabled: open && state.packageVersion !== '' && getPackage !== undefined,
  });
  const roleGroups = packageQuery.data === undefined
    ? []
    : deriveComponentRoleGroups(packageQuery.data, selectedPackageVersion);

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

  // The Connection picker (plan 014) — fetched once the dialog opens, and
  // only while `dp-remote` is the mode (the only one with a Connection
  // binding). `listConnections` is organization-scoped server-side (`I5`:
  // it takes no filter), and the repository keeps only ACTIVE
  // `remote-server` rows, so the picker never offers a broker or a retired
  // host. Only non-secret metadata (id, name) is shown — never the host or
  // a credential.
  const onDpRemote = state.deploymentMode === 'dp-remote';
  const connectionsQuery = useQuery({
    queryKey: queryKeys.connections.remoteServers(),
    queryFn: ({ signal }) => connectionsRepository.listRemoteServers(signal),
    enabled: open && onDpRemote,
  });
  const connections = connectionsQuery.data ?? [];
  const targetsQuery = useQuery({
    queryKey: ['deployment-targets'],
    queryFn: ({ signal }) => targetsRepository!.list(signal),
    enabled: open && onDpRemote && targetsRepository !== undefined,
  });
  const targets = targetsQuery.data ?? [];
  const selectedTarget = targets.find((target) => target.targetId === state.targetId);
  const systemdTarget = selectedTarget?.runtime === 'systemd';

  const toOptions = (values: readonly string[]): { value: string; label: string }[] =>
    values.map((value) => ({ value, label: value }));

  const organizationOptions = toOptions(organizationsQuery.data ?? []);
  const subOrgOptions = toOptions(subOrgsQuery.data ?? []);
  const environmentOptions = toOptions(environmentsQuery.data ?? []);
  const connectionOptions = connections.map((connection) => ({
    value: connection.connectionId,
    label: connection.name,
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
      packageVersionUnknown: t('instancesDeclareErrPackageVersion'),
      healthPortRange: t('instancesDeclareErrHealthPortRange'),
      deploymentModeUnavailable: t('instancesDeclareErrDeploymentModeUnavailable'),
    },
    packageVersions,
    connections,
    roleGroups,
    targets,
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
  // A loaded-but-empty list is its own fact ("add one first"), distinct
  // from a failed load and from the default helper copy.
  const connectionsEmpty =
    connectionsQuery.isSuccess && !connectionsQuery.isFetching && connections.length === 0;
  const connectionHelp =
    visible.connectionId ??
    levelHelp(connectionsQuery, true, '') ??
    (connectionsEmpty ? t('instancesDeclareConnectionNone') : t('instancesDeclareConnectionHelp'));

  async function handleSubmit(): Promise<void> {
    setShowErrors(true);
    setSubmitError(undefined);
    if (hasErrors(errors)) {
      return;
    }
    try {
      const environmentId = await hierarchyRepository.environmentId(state.org, state.subOrg, state.env);
      onSubmit(environmentId, toDeclareInput(state, solutionId, packageVersions, roleGroups, selectedTarget?.runtime));
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : t('errorGeneric'));
    }
  }

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
          <Button onClick={() => void handleSubmit()} disabled={submitting}>
            {submitting ? t('instancesDeclareSubmitting') : t('instancesDeclareSubmit')}
          </Button>
        </>
      }
    >
      <Stack gap={2.5}>
        <Typography variant="body2">{t('instancesDeclareDialogIntro')}</Typography>

        {(errorMessage !== undefined || submitError !== undefined) && (
          <Alert severity="err" title={t('instancesDeclareFailedTitle')}>
            {errorMessage ?? submitError}
          </Alert>
        )}

        <TextField
          label={t('instancesDeclareName')}
          required
          autoFocus
          value={state.name}
          onChange={(event) => setState((prev) => ({ ...prev, name: event.target.value }))}
          {...(visible.name !== undefined
            ? { error: visible.name }
            : { helperText: t('instancesDeclareNameHelp') })}
        />

        {/* Directly under Name: it is IDENTITY, and it decides which
            fields the Designer will render for this instance. */}
        <Select
          label={t('instancesDeclarePackageVersion')}
          required
          value={state.packageVersion}
          onChange={(packageVersion) => setState((prev) => applyPackageVersion(prev, packageVersion))}
          options={packageVersionOptions}
          {...(visible.packageVersion !== undefined
            ? { error: visible.packageVersion }
            : { helperText: t('instancesDeclarePackageVersionHelp') })}
        />

        {roleGroups.length > 0 && (
          <Select
            label={t('instancesDeclareRole')}
            required={roleGroups.length > 1}
            value={roleGroups.length === 1 ? roleGroups[0]!.role : state.role}
            onChange={(role) => setState((prev) => ({ ...prev, role }))}
            options={roleGroups.map(({ role }) => ({ value: role, label: role }))}
            disabled={roleGroups.length === 1}
            {...(visible.role !== undefined
              ? { error: visible.role }
              : { helperText: t('instancesDeclareRoleHelp') })}
          />
        )}

        {/* The cascade, in the order it must be answered (`FE-D46`):
            Organization → Sub-org → Environment. */}
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

        <Select
          label={t('instancesDeclareEnv')}
          required
          value={state.env}
          onChange={(env) => setState((prev) => setEnv(prev, env))}
          options={environmentOptions}
          disabled={state.subOrg === ''}
          {...(visible.env !== undefined
            ? { error: visible.env }
            : envHelp !== undefined
              ? { helperText: envHelp }
              : {})}
        />

        {/* How the instance is deployed (plan 014). All three modes are
            listed; only dp-remote can be chosen today. */}
        <Select
          label={t('instancesDeclareDeploymentMode')}
          required
          value={state.deploymentMode}
          onChange={(mode) => setState((prev) => setDeploymentMode(prev, mode as DeploymentMode))}
          options={DEPLOYMENT_MODE_OPTIONS}
          {...(visible.deploymentMode !== undefined
            ? { error: visible.deploymentMode }
            : { helperText: t('instancesDeclareDeploymentModeHelp') })}
        />

        {/* The dp-remote host binding: a remote-server Connection (plan
            014), in place of plan 007's target picker — never both. */}
        {onDpRemote && (
          targetsRepository !== undefined ? (
            <Select
              label="Deployment target"
              required
              value={state.targetId}
              onChange={(targetId) => setState((prev) => setTarget(prev, targetId, targets))}
              options={targets.map((target) => ({
                value: target.targetId,
                label: `${target.name} (${target.runtime ?? target.kind})`,
              }))}
              disabled={state.env === ''}
              {...(visible.connectionId !== undefined
                ? { error: visible.connectionId }
                : { helperText: targetsQuery.isFetching ? 'Loading deployment targets…' : 'Select the runtime target for this instance.' })}
            />
          ) : (
            <Select
              label={t('instancesDeclareConnection')}
              required
              value={state.connectionId}
              onChange={(connectionId) => setState((prev) => ({ ...prev, connectionId }))}
              options={connectionOptions}
              disabled={state.env === ''}
              {...(visible.connectionId !== undefined
                ? { error: visible.connectionId }
                : { helperText: connectionHelp })}
            />
          )
        )}

        <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
          <TextField
            label={systemdTarget ? 'Service/unit name' : t('instancesDeclareContainerName')}
            required
            value={state.containerName}
            onChange={(event) => setState((prev) => ({ ...prev, containerName: event.target.value }))}
            {...(visible.containerName !== undefined
              ? { error: visible.containerName }
              : { helperText: systemdTarget ? 'Must match the target service-name pattern.' : t('instancesDeclareContainerNameHelp') })}
          />
          <TextField
            label={t('instancesDeclareFileName')}
            required
            value={state.fileName}
            onChange={(event) => setState((prev) => ({ ...prev, fileName: event.target.value }))}
            {...(visible.fileName !== undefined ? { error: visible.fileName } : {})}
          />
        </Grid>

        <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
          <TextField
            label={t('instancesDeclareConfigDirectory')}
            required
            value={state.configDirectory}
            onChange={(event) => setState((prev) => ({ ...prev, configDirectory: event.target.value }))}
            {...(visible.configDirectory !== undefined
              ? { error: visible.configDirectory }
              : { helperText: t('instancesDeclareConfigDirectoryHelp') })}
          />
          {!systemdTarget && (
            <TextField
              label={t('instancesDeclareContainerConfigPath')}
              required
              value={state.containerConfigPath}
              onChange={(event) =>
                setState((prev) => ({ ...prev, containerConfigPath: event.target.value }))
              }
              {...(visible.containerConfigPath !== undefined
                ? { error: visible.containerConfigPath }
                : {})}
            />
          )}
        </Grid>

        <Grid columns={{ xs: 1, sm: 2 }} gap={2}>
          <TextField
            label={t('instancesDeclareHealthPort')}
            required
            value={state.healthPort}
            onChange={(event) => setState((prev) => ({ ...prev, healthPort: event.target.value }))}
            {...(visible.healthPort !== undefined ? { error: visible.healthPort } : {})}
          />
          <Select
            label={t('instancesDeclareAcquisitionMode')}
            required
            value={state.acquisitionMode}
            onChange={(mode) =>
              setState((prev) => ({ ...prev, acquisitionMode: mode as AcquisitionMode }))
            }
            options={ACQUISITION_OPTIONS}
            {...(visible.acquisitionMode !== undefined
              ? { error: visible.acquisitionMode }
              : { helperText: t('instancesDeclareAcquisitionModeHelp') })}
          />
        </Grid>
      </Stack>
    </Dialog>
  );
}
