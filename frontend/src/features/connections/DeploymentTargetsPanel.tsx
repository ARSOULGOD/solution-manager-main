import { Box, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { ApiError } from '@shared/api';
import { queryKeys } from '@shared/api/queryKeys';
import type { TargetRow, TargetsRepository } from '@shared/api/targetsRepository';
import { Button, ConfirmDialog, Dialog, EmptyState, PageHeader, Paper, Select, TextField } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { PageWidth, Stack } from '@shared/ui/layout';

import type { ConnectionRow, ConnectionsRepository } from './connectionsRepository';

export interface DeploymentTargetsPanelProps {
  connectionsRepository: ConnectionsRepository;
  targetsRepository: TargetsRepository;
}

type Runtime = 'docker' | 'systemd';

interface Draft {
  name: string;
  connectionId: string;
  runtime: Runtime;
  configRoot: string;
  containerPrefix: string;
  serviceNamePattern: string;
}

const emptyDraft: Draft = {
  name: '', connectionId: '', runtime: 'docker', configRoot: '', containerPrefix: '', serviceNamePattern: '',
};

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

function settings(target: TargetRow): Record<string, string> {
  if (target.runtimeSettingsJson === undefined) return {};
  try {
    const parsed: unknown = JSON.parse(target.runtimeSettingsJson);
    return parsed !== null && typeof parsed === 'object'
      ? Object.fromEntries(Object.entries(parsed).filter(([, value]) => typeof value === 'string'))
      : {};
  } catch {
    return {};
  }
}

function runtimeOf(target: TargetRow): Runtime {
  return target.runtime === 'systemd' || target.kind === 'systemd' ? 'systemd' : 'docker';
}

function draftFor(target: TargetRow): Draft {
  const runtime = runtimeOf(target);
  const targetSettings = settings(target);
  return {
    name: target.name,
    connectionId: target.connectionId ?? '',
    runtime,
    configRoot: targetSettings.configRoot ?? target.allowedConfigRoot ?? '',
    containerPrefix: targetSettings.containerPrefix ?? target.allowedContainerPrefix ?? '',
    serviceNamePattern: targetSettings.serviceNamePattern ?? '',
  };
}

export function DeploymentTargetsPanel({ connectionsRepository, targetsRepository }: DeploymentTargetsPanelProps) {
  const queryClient = useQueryClient();
  const [editingTarget, setEditingTarget] = useState<TargetRow | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [confirmingTarget, setConfirmingTarget] = useState<TargetRow | null>(null);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [saving, setSaving] = useState(false);
  const [retiring, setRetiring] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const connectionsQuery = useQuery({
    queryKey: queryKeys.connections.list(),
    queryFn: ({ signal }) => connectionsRepository.list(signal),
  });
  const targetsQuery = useQuery({
    queryKey: queryKeys.targets.list(),
    queryFn: ({ signal }) => targetsRepository.list(signal),
  });
  const remoteConnections = useMemo(
    () => (connectionsQuery.data ?? []).filter((row) => row.connectionType === 'remote-server' && row.active),
    [connectionsQuery.data],
  );
  const connectionName = new Map(remoteConnections.map((row) => [row.connectionId, row.name]));
  function openCreate() {
    setActionError(null);
    setSaveError(null);
    setEditingTarget(null);
    setDraft(emptyDraft);
    setFormOpen(true);
  }

  function openEdit(target: TargetRow) {
    if (target.connectionId === undefined) return;
    setActionError(null);
    setSaveError(null);
    setEditingTarget(target);
    setDraft(draftFor(target));
    setFormOpen(true);
  }

  function closeDialog() {
    if (!saving) {
      setEditingTarget(null);
      setDraft(emptyDraft);
      setSaveError(null);
      setFormOpen(false);
    }
  }

  async function saveTarget() {
    setSaving(true);
    setSaveError(null);
    const input = {
      name: draft.name.trim(),
      connectionId: draft.connectionId,
      kind: draft.runtime,
      configRoot: draft.configRoot.trim(),
      ...(draft.runtime === 'docker'
        ? { containerPrefix: draft.containerPrefix.trim() }
        : { serviceNamePattern: draft.serviceNamePattern.trim() }),
    };
    try {
      if (editingTarget === null) {
        await targetsRepository.registerLinked({ ...input, ownershipScope: {} });
      } else {
        await targetsRepository.updateLinked(editingTarget.targetId, input);
      }
      await queryClient.invalidateQueries({ queryKey: queryKeys.targets.list() });
      setFormOpen(false);
      setEditingTarget(null);
      setDraft(emptyDraft);
    } catch (error) {
      setSaveError(errorText(error, editingTarget === null
        ? 'Could not create the deployment target.'
        : 'Could not update the deployment target.'));
    } finally {
      setSaving(false);
    }
  }

  async function retireTarget() {
    if (confirmingTarget === null) return;
    setRetiring(true);
    setActionError(null);
    try {
      await targetsRepository.retire(confirmingTarget.targetId);
      await queryClient.invalidateQueries({ queryKey: queryKeys.targets.list() });
      setConfirmingTarget(null);
    } catch (error) {
      setActionError(errorText(error, 'Could not retire the deployment target.'));
    } finally {
      setRetiring(false);
    }
  }

  const canSave = draft.name.trim() !== '' && draft.connectionId !== '' && draft.configRoot.trim().startsWith('/')
    && !draft.configRoot.includes('..')
    && (draft.runtime === 'docker' ? draft.containerPrefix.trim() !== '' : draft.serviceNamePattern.trim() !== '');

  return (
    <PageWidth size="wide">
      <Stack gap={3}>
        <PageHeader
          title="Deployment targets"
          sub="Runtime-specific placement settings under a reusable remote connection."
          actions={<Button variant="outlined" size="sm" onClick={openCreate}>New target</Button>}
        />

        {actionError === null ? null : <ErrorPanel title="Deployment target action failed" detail={actionError} />}
        {targetsQuery.isError ? <ErrorPanel title="Deployment targets unavailable" detail={errorText(targetsQuery.error, 'Could not load deployment targets.')} /> : null}
        {targetsQuery.isPending ? <Typography color="text.secondary">Loading targets…</Typography> : null}
        {!targetsQuery.isPending && !targetsQuery.isError && (targetsQuery.data ?? []).length === 0 ? (
          <Paper surface="flat"><EmptyState title="No deployment targets" body="Add a Docker or systemd target under an existing remote-server connection." /></Paper>
        ) : null}
        {(targetsQuery.data ?? []).length > 0 ? (
          <Paper surface="flat">
            <Box sx={{ overflowX: 'auto' }} data-reflow-exception="data-table">
              <Table size="small">
                <TableHead>
                  <TableRow sx={{ '& th': { fontWeight: 600, backgroundColor: 'action.hover', color: 'text.secondary' } }}>
                    <TableCell>Name</TableCell>
                    <TableCell>Runtime</TableCell>
                    <TableCell>Remote connection</TableCell>
                    <TableCell>Configuration root</TableCell>
                    <TableCell>Runtime rule</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(targetsQuery.data ?? []).map((target) => {
                    const targetSettings = settings(target);
                    const runtime = runtimeOf(target);
                    const rule = runtime === 'docker'
                      ? targetSettings.containerPrefix ?? target.allowedContainerPrefix ?? '—'
                      : targetSettings.serviceNamePattern ?? '—';
                    return (
                      <TableRow key={target.targetId} hover>
                        <TableCell sx={{ fontWeight: 600 }}>{target.name}</TableCell>
                        <TableCell>{runtime === 'docker' ? 'Docker' : 'systemd'}</TableCell>
                        <TableCell>{target.connectionId === undefined ? 'Legacy target' : (connectionName.get(target.connectionId) ?? target.connectionId)}</TableCell>
                        <TableCell>{targetSettings.configRoot ?? target.allowedConfigRoot ?? '—'}</TableCell>
                        <TableCell>{rule}</TableCell>
                        <TableCell align="right">
                          <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end' }}>
                            <Button size="sm" variant="text" disabled={target.connectionId === undefined} onClick={() => openEdit(target)}>Edit</Button>
                            <Button size="sm" variant="danger" onClick={() => setConfirmingTarget(target)}>Delete</Button>
                          </Box>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </Box>
          </Paper>
        ) : null}
      </Stack>

      <Dialog
        open={formOpen}
        title={editingTarget === null ? 'New deployment target' : 'Edit deployment target'}
        onClose={closeDialog}
        dirty={saving}
        maxWidth="sm"
        actions={<><Button variant="text" onClick={closeDialog}>Cancel</Button><Button disabled={!canSave || saving || remoteConnections.length === 0} onClick={() => void saveTarget()}>{editingTarget === null ? 'Create target' : 'Save changes'}</Button></>}
      >
        <Stack gap={2}>
          {saveError === null ? null : <ErrorPanel title="Could not save target" detail={saveError} />}
          {connectionsQuery.isError ? <ErrorPanel title="Remote connections unavailable" detail={errorText(connectionsQuery.error, 'Could not load remote connections.')} /> : null}
          <TextField label="Target name" required value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} />
          <Select label="Remote connection" required value={draft.connectionId} disabled={editingTarget !== null} onChange={(value) => setDraft({ ...draft, connectionId: value })} options={remoteConnections.map((row: ConnectionRow) => ({ value: row.connectionId, label: row.name }))} />
          <Select label="Runtime" required value={draft.runtime} disabled={editingTarget !== null} onChange={(value) => setDraft({ ...draft, runtime: value as Runtime, containerPrefix: '', serviceNamePattern: '' })} options={[{ value: 'docker', label: 'Docker' }, { value: 'systemd', label: 'systemd' }]} />
          <TextField label="Configuration root" required value={draft.configRoot} onChange={(event) => setDraft({ ...draft, configRoot: event.target.value })} helperText="Absolute host path. Instance placement must stay inside it." />
          {draft.runtime === 'docker' ? <TextField label="Allowed container prefix" required value={draft.containerPrefix} onChange={(event) => setDraft({ ...draft, containerPrefix: event.target.value })} helperText="Container names must start with this prefix." /> : <TextField label="Service name pattern" required value={draft.serviceNamePattern} onChange={(event) => setDraft({ ...draft, serviceNamePattern: event.target.value })} helperText="A permitted systemd unit prefix or regular expression." />}
        </Stack>
      </Dialog>

      <ConfirmDialog
        open={confirmingTarget !== null}
        title="Delete deployment target?"
        consequence="high"
        confirmLabel={retiring ? 'Deleting…' : 'Delete target'}
        dirty={retiring}
        onCancel={() => { if (!retiring) setConfirmingTarget(null); }}
        onConfirm={() => void retireTarget()}
      >
        <Typography>This retires the target and removes it from new instance placement choices. Existing references must be settled first.</Typography>
      </ConfirmDialog>
    </PageWidth>
  );
}
