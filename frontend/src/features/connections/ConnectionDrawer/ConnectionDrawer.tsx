/**
 * Connection detail/edit drawer (task 8, `UI-D27`, spec §8.2; rewired to
 * the real `console.yaml` contract by plan 013 Task 5).
 *
 * **Standalone by design.** `ConnectionsPage`/`ConnectionsRoute`'s
 * `onOpen`/`onCreate` callbacks open this; it takes `connection`/`descriptor`/
 * `onSave`/`onClose` directly and knows nothing about the catalog list or a
 * repository. `descriptor` is supplied by the caller (fetched via
 * `getConnectionTypes`, task 5) — this file holds no static `DESCRIPTORS`
 * import.
 *
 * **Two explicit save actions** (task 5 brief): "Save" sends
 * `testBeforeSave: false`, "Test & Save" sends `testBeforeSave: true` — the
 * one flag `CreateConnectionRequest`/`UpdateConnectionRequest` both declare;
 * there is no separate test-then-save round trip. `onSave` returns a
 * `Promise` that the drawer awaits itself (rather than firing-and-closing)
 * so a `422 CONNECTION_TEST_FAILED` can keep the drawer open.
 *
 * **On `422 CONNECTION_TEST_FAILED`:** the drawer stays open, the peer's
 * rejection reason (`ApiError.message`, sourced from `Problem.detail`)
 * renders inline, and nothing entered is touched — `values`/`secretDrafts`
 * are never cleared on a failed save, so every non-secret field and every
 * secret's typed draft (never its prior value, `I6`) is exactly as the
 * operator left it. The same holds for `429 RATE_LIMITED` (shows
 * `Retry-After`) and `503 SERVICE_UNAVAILABLE` (the probe could not reach
 * the peer) — any `ApiError` keeps the drawer open and renders inline;
 * only a successful save closes it (the caller's `onSave` resolves and the
 * caller itself calls `onClose`).
 *
 * **Secrets — no read path, ever (`I6`).** `SecretField`
 * (`@shared/ui/SecretField`) is a plain labelled password field with a
 * show/hide toggle. A secret attribute's "entered" state is `secretStatus`,
 * derived from `connection.attributes[key]` being the gateway's `{ set:
 * true }` redaction shape (never a value) — the field always renders empty,
 * its helper text says whether a value is currently stored, and the typed
 * draft (`secretDrafts`, per key) is sent forward on save only when
 * non-empty.
 *
 * **`UI-D4` retention.** `values` (non-secret attributes) and
 * `secretDrafts` (per-key typed text) are each ONE object keyed by
 * attribute key, never cleared when `visibleAttributes` stops including
 * that key — switching `authMethod` away and back reveals whatever was
 * typed before, because nothing was ever deleted, only filtered out of
 * the render.
 *
 * **No `description` field.** The real contract's `Connection`/
 * `CreateConnectionRequest`/`UpdateConnectionRequest` are all
 * `additionalProperties: false` and none declares one — the pre-Task-5
 * fixture had one; the field and its input are gone.
 *
 * **No visual `required` asterisk.** MUI's `TextField`/`NumberField`
 * append literal " *" to the rendered `<label>` text when `required` is
 * passed — confirmed by inspecting the rendered DOM while writing this
 * file. That text lands inside `getByLabelText`'s exact match (RTL reads
 * the `<label>`'s `textContent`, which is not accessible-name computation
 * and does not drop `aria-hidden` children), so an exact `getByLabelText`
 * on a required field's plain label would never match. Every required
 * check still runs, in `validateConnection`, and its error renders as the
 * field's helper text once `showErrors` is true.
 */
import { Box, Button as MuiButton, TextField as MuiTextField, Typography } from '@mui/material';
import { useId, useState } from 'react';

import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Button, ConfirmDialog, Dialog, NumberField, SecretField, Switch, TextField } from '@shared/ui';
import { ErrorPanel } from '@shared/ui/ErrorPanel';
import { Stack } from '@shared/ui/layout';

import type { ConnectionDescriptor, ConnectionDescriptorAttribute, ConnectionRow, ConnectionWriteInput }
  from '../connectionsRepository';

import { validateConnection, visibleAttributes } from './descriptorForm';

export interface ConnectionDrawerProps {
  connection: ConnectionRow;
  descriptor: ConnectionDescriptor;
  title?: string;
  /** Rejected with an `ApiError` (or any other error) keeps the drawer open; resolved lets the caller close it. */
  onSave: (input: ConnectionWriteInput) => Promise<void>;
  onClose: () => void;
  /**
   * Task 10's deactivate action — absent while creating a new connection
   * (`connection.connectionId === ''`), since there is nothing yet to
   * deactivate. One-directional: the real contract has no reactivate
   * operation, so this is a button, never a toggle.
   */
  onDeactivate?: () => void;
  /**
   * Task 10's delete action. `connection.usedByCount > 0` disables the
   * control here, before any request is made — the repository's own
   * `remove()` refuses it too (spec §6.3), so this is a better error
   * message, not the only guard.
   */
  onDelete?: () => void;
}

function formatScope(scope: ConnectionRow['ownershipScope']): string {
  const ids = [scope.subOrganizationId, scope.environmentId, scope.solutionId, scope.instanceId].filter(
    (id): id is string => id !== undefined,
  );
  return ids.length === 0 ? t('targetsPreviewScopeOrganizationWide') : ids.join(' / ');
}

/** Whether an attribute's current value, as received over the wire, is the `{ set: true }` secret redaction. */
function isSecretSet(value: unknown): boolean {
  return (
    value !== null &&
    typeof value === 'object' &&
    (value as { set?: unknown }).set === true
  );
}

/**
 * `error`/`helperText` for a `FieldProps`-shaped control, built so neither
 * key is ever passed as `undefined` — `exactOptionalPropertyTypes` treats
 * an explicit `undefined` differently from an omitted key, and every
 * shared field wrapper's own props are typed `helperText?: string`, not
 * `string | undefined`.
 */
function fieldStatusProps(
  error: string | undefined,
  helperText: string | undefined,
): { error: string } | { helperText: string } | Record<string, never> {
  if (error !== undefined) return { error };
  if (helperText !== undefined) return { helperText };
  return {};
}

/**
 * The discriminator/enum control, as a native `<select>` under MUI's own
 * `TextField` styling (`slotProps.select.native`). The shared `Select`
 * component (`@shared/ui`) renders MUI's non-native listbox, which has no
 * real `<select>` element for `userEvent.selectOptions` (or any
 * `<select>`-shaped assistive tech interaction) to drive — verified by
 * running both variants under this task's own test before choosing this
 * one. This keeps the visual language identical to `Select` while giving
 * the discriminator swap a real native control.
 */
function EnumField({
  attribute,
  value,
  error,
  onChange,
}: {
  attribute: ConnectionDescriptorAttribute;
  value: string;
  error: string | undefined;
  onChange: (value: string) => void;
}) {
  const options = (attribute.options ?? []).map((option) => String(option));
  return (
    <MuiTextField
      select
      label={attribute.ui?.label ?? attribute.key}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      error={error !== undefined}
      helperText={error ?? attribute.ui?.helperText}
      fullWidth
      slotProps={{ select: { native: true } }}
    >
      {options.map((option) => (
        <option key={option} value={option}>
          {option}
        </option>
      ))}
    </MuiTextField>
  );
}

/**
 * One secret attribute, rendered as a plain write-only password field
 * (`SecretField`'s own eye-icon reveal, no separate set/replace step).
 * `status` only ever drives the helper text — while the draft is empty,
 * it says whether a value is currently stored, without ever fetching or
 * displaying it. Typing replaces that helper with the field's own
 * validation/description text, same as any other attribute.
 */
function SecretAttributeField({
  attribute,
  status,
  draft,
  error,
  onDraftChange,
}: {
  attribute: ConnectionDescriptorAttribute;
  status: 'set' | 'unset';
  draft: string;
  error: string | undefined;
  onDraftChange: (value: string) => void;
}) {
  const label = attribute.ui?.label ?? attribute.key;
  const helperText =
    draft === ''
      ? (status === 'set'
          ? t('connectionsDrawerSecretCurrentlySet')
          : t('connectionsDrawerSecretUnset'))
      : attribute.ui?.helperText;
  return (
    <SecretField
      label={label}
      value={draft}
      onChange={(event) => onDraftChange(event.target.value)}
      {...(attribute.key === 'privateKey' ? { multiline: true, rows: 8 } : {})}
      {...fieldStatusProps(error, helperText)}
    />
  );
}

/** Message rendered inline for a rejected save — branches on `ApiError.code`/`status` (never `title`, `I5`-adjacent convention). */
function describeSaveError(error: unknown): { detail: string; code?: string } {
  if (!(error instanceof ApiError)) {
    return { detail: t('errorGeneric') };
  }
  if (error.code === 'CONNECTION_TEST_FAILED') {
    return { detail: error.message, ...(error.code === undefined ? {} : { code: error.code }) };
  }
  if (error.status === 429) {
    const retry = error.retryAfterSeconds;
    return {
      detail: retry === undefined ? error.message : `${error.message} Retry in ${String(retry)}s.`,
      ...(error.code === undefined ? {} : { code: error.code }),
    };
  }
  return { detail: error.message, ...(error.code === undefined ? {} : { code: error.code }) };
}

export function ConnectionDrawer({
  connection,
  descriptor,
  title = connection.name,
  onSave,
  onClose,
  onDeactivate,
  onDelete,
}: ConnectionDrawerProps) {
  const deleteBlockedReasonId = useId();
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const [name, setName] = useState(connection.name);
  const [values, setValues] = useState<Record<string, unknown>>(() => {
    const nonSecret: Record<string, unknown> = {};
    for (const attribute of descriptor.attributes) {
      const key = attribute.key;
      const value = connection.attributes[key];
      if (!isSecretSet(value)) {
        nonSecret[key] = value ?? attribute.default;
      }
    }
    return nonSecret;
  });
  const [secretDrafts, setSecretDrafts] = useState<Record<string, string>>({});
  const [showErrors, setShowErrors] = useState(false);
  const [confirmingImpact, setConfirmingImpact] = useState<boolean | 'test'>(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<{ detail: string; code?: string } | null>(null);

  const secretStatus: Record<string, 'set' | 'unset'> = {};
  for (const attribute of descriptor.attributes) {
    if (attribute.secret === true) {
      secretStatus[attribute.key] = isSecretSet(connection.attributes[attribute.key]) ? 'set' : 'unset';
    }
  }

  const visible = visibleAttributes(descriptor, values);

  // Only for validation: a secret being typed right now counts toward its
  // own "required" check via the value path `validateConnection` already
  // has (never toward any OTHER field, and never persisted into `values`,
  // which holds non-secret attributes only).
  const valuesForValidation: Record<string, unknown> = { ...values };
  for (const [key, draft] of Object.entries(secretDrafts)) {
    if (draft !== '') {
      valuesForValidation[key] = draft;
    }
  }
  const errors = validateConnection(descriptor, valuesForValidation, secretStatus);
  const visibleErrors = showErrors ? errors : {};

  function buildInput(testBeforeSave: boolean): ConnectionWriteInput {
    const attributes: Record<string, unknown> = {};
    for (const attribute of visible) {
      if (attribute.secret === true) {
        const draft = secretDrafts[attribute.key];
        if (draft !== undefined && draft !== '') {
          attributes[attribute.key] = draft;
        }
        continue;
      }
      attributes[attribute.key] = values[attribute.key];
    }
    return {
      name,
      connectionType: connection.connectionType,
      attributes,
      testBeforeSave,
    };
  }

  async function doSave(testBeforeSave: boolean): Promise<void> {
    setSaving(true);
    setSaveError(null);
    try {
      await onSave(buildInput(testBeforeSave));
      // Success: the caller's `onSave` is responsible for closing (and
      // invalidating the list query) — nothing further happens here.
    } catch (error) {
      setSaveError(describeSaveError(error));
    } finally {
      setSaving(false);
    }
  }

  function handleSaveClick(testBeforeSave: boolean): void {
    setShowErrors(true);
    if (Object.keys(errors).length > 0) {
      return;
    }
    if (connection.usedByCount > 0) {
      setConfirmingImpact(testBeforeSave ? 'test' : true);
      return;
    }
    void doSave(testBeforeSave);
  }

  function handleConfirmImpact(): void {
    const testBeforeSave = confirmingImpact === 'test';
    setConfirmingImpact(false);
    void doSave(testBeforeSave);
  }

  function renderAttribute(attribute: ConnectionDescriptorAttribute) {
    const key = attribute.key;
    const error = visibleErrors[key];

    if (attribute.secret === true || attribute.type === 'secret') {
      const status: 'set' | 'unset' = secretStatus[key] === 'set' ? 'set' : 'unset';
      return (
        <SecretAttributeField
          key={key}
          attribute={attribute}
          status={status}
          draft={secretDrafts[key] ?? ''}
          error={error}
          onDraftChange={(draftValue) =>
            setSecretDrafts((prev) => ({ ...prev, [key]: draftValue }))
          }
        />
      );
    }

    if (attribute.type === 'enum') {
      return (
        <EnumField
          key={key}
          attribute={attribute}
          value={typeof values[key] === 'string' ? values[key] : String(attribute.default ?? '')}
          error={error}
          onChange={(value) => setValues((prev) => ({ ...prev, [key]: value }))}
        />
      );
    }

    if (attribute.type === 'bool') {
      return (
        <Switch
          key={key}
          label={attribute.ui?.label ?? key}
          checked={values[key] === true}
          onChange={(checked) => setValues((prev) => ({ ...prev, [key]: checked }))}
          {...fieldStatusProps(error, attribute.ui?.helperText)}
        />
      );
    }

    if (attribute.type === 'int') {
      const rawValue = values[key];
      return (
        <NumberField
          key={key}
          label={attribute.ui?.label ?? key}
          value={typeof rawValue === 'number' ? rawValue : ''}
          onChange={(value) => setValues((prev) => ({ ...prev, [key]: value }))}
          {...fieldStatusProps(error, attribute.ui?.helperText)}
        />
      );
    }

    // 'string' and the unused-today 'duration' both read as plain text.
    const rawValue = values[key];
    const textValue =
      typeof rawValue === 'string'
        ? rawValue
        : typeof rawValue === 'number' || typeof rawValue === 'boolean'
          ? String(rawValue)
          : '';
    return (
      <TextField
        key={key}
        label={attribute.ui?.label ?? key}
        value={textValue}
        onChange={(event) => setValues((prev) => ({ ...prev, [key]: event.target.value }))}
        {...fieldStatusProps(error, attribute.ui?.helperText)}
      />
    );
  }

  return (
    <>
      <Dialog
        open
        title={title}
        onClose={onClose}
        dirty
        maxWidth="md"
        actions={
          <>
            <Button variant="text" onClick={onClose}>
              {t('cancel')}
            </Button>
            <Button variant="outlined" disabled={saving} onClick={() => handleSaveClick(true)}>
              {t('connectionsDrawerTestAndSaveAction')}
            </Button>
            <Button disabled={saving} onClick={() => handleSaveClick(false)}>
              {t('connectionsDrawerSaveAction')}
            </Button>
          </>
        }
      >
        <Stack gap={2.5}>
          {saveError === null ? null : (
            <ErrorPanel
              title={t('connectionsDrawerSaveErrorTitle')}
              detail={saveError.detail}
              {...(saveError.code === undefined ? {} : { code: saveError.code })}
            />
          )}
          <TextField
            label={t('connectionsDrawerNameLabel')}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
          {/* Read-only: scope is server-derived and never editable (`I5`). */}
          <TextField
            label={t('connectionsDrawerScopeLabel')}
            value={formatScope(connection.ownershipScope)}
            onChange={() => {}}
            readOnly
          />

          {visible.map((attribute) => renderAttribute(attribute))}

          {onDeactivate === undefined && onDelete === undefined ? null : (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
              {onDeactivate === undefined ? null : (
                <MuiButton
                  variant="outlined"
                  size="small"
                  disabled={!connection.active}
                  onClick={() => onDeactivate()}
                >
                  {t('connectionsDrawerDeactivate')}
                </MuiButton>
              )}
              {onDelete === undefined ? null : (
                <>
                  <MuiButton
                    variant="outlined"
                    color="error"
                    size="small"
                    disabled={connection.usedByCount > 0}
                    aria-describedby={connection.usedByCount > 0 ? deleteBlockedReasonId : undefined}
                    onClick={() => setConfirmingDelete(true)}
                  >
                    {t('connectionsDrawerDelete')}
                  </MuiButton>
                  {connection.usedByCount > 0 && (
                    <Typography
                      id={deleteBlockedReasonId}
                      variant="caption"
                      color="text.secondary"
                    >
                      {t('connectionsDrawerDeleteBlockedReason')}
                    </Typography>
                  )}
                </>
              )}
              <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>
                {t('connectionsDrawerUsedBy', { count: connection.usedByCount })}
              </Typography>
            </Box>
          )}

        </Stack>
      </Dialog>

      <ConfirmDialog
        open={confirmingImpact !== false}
        title={t('connectionsDrawerImpactTitle')}
        onConfirm={handleConfirmImpact}
        onCancel={() => setConfirmingImpact(false)}
        confirmLabel={t('connectionsDrawerSaveAction')}
      >
        {t('connectionsDrawerImpactBody', { count: connection.usedByCount })}
      </ConfirmDialog>

      {onDelete === undefined ? null : (
        <ConfirmDialog
          open={confirmingDelete}
          title={t('connectionsDrawerDeleteConfirmTitle')}
          onConfirm={() => {
            setConfirmingDelete(false);
            onDelete();
          }}
          onCancel={() => setConfirmingDelete(false)}
          confirmLabel={t('connectionsDrawerDelete')}
        >
          {t('connectionsDrawerDeleteConfirmBody')}
        </ConfirmDialog>
      )}
    </>
  );
}
