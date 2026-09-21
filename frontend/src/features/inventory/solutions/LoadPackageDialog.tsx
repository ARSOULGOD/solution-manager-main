/**
 * Load-a-package dialog (task 040, `UI-D19`, `UI-D11`, UI §A.5 load package
 * dialog). Drop zone + file picker → canonical schema/semantic preflight
 * in the browser → readable summary → signature-verification call →
 * explicit confirmation.
 *
 * **Admission rule** (`UI-D19`): a signed package is always admitted. An
 * unsigned one is admitted only when THIS console is itself running in
 * development mode — `import.meta.env.DEV`, the same static,
 * build-time-literal branch `AuthProvider.tsx`/`devMockAdapter.ts` already
 * gate on, not merely because the server reports `devMode: true`. That
 * second half (console not in dev mode → rejected) cannot be exercised by
 * a Vitest run, since Vitest always sets `import.meta.env.DEV = true` (see
 * `devMockAdapter.ts`'s own header) — the guard is a one-line boolean
 * short-circuit, correct by inspection, the same posture `main.tsx`'s dev
 * MSW bootstrap takes for its own untestable-in-Vitest branch.
 *
 * The HTTP seam stages the selected file after local validation. Browser
 * preflight improves feedback but is never the trust boundary; the backend
 * repeats package admission before a staged package may be confirmed.
 *
 * **The confirm/dismiss edges are announced to the repository.** An
 * implementation that holds per-selection state between `load()` and the
 * operator's answer needs to know which way the answer went; the dev
 * package loader (FE-114) parses the dropped file's bytes locally and must
 * not put the result in the catalog until Load is clicked. Both hooks are
 * OPTIONAL for fixture repositories. The live repository stages the package
 * until the operator confirms or dismisses it.
 */
import { Box, CircularProgress, Typography, useTheme } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import type { ExistingPackageRange, PackageValidationResult } from '@contracts/solution-package';
import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Dialog, ToneIcon } from '@shared/ui';

import { UploadIcon } from './icons';
import { inspectPackageFile } from './inspectPackageFile';
import { PackageValidationSummary } from './PackageValidationSummary';
import type { PackageLoadResult, SolutionsRepository } from './solutionsRepository';

export interface LoadPackageDialogProps {
  open: boolean;
  onClose: () => void;
  onLoaded: (result: PackageLoadResult) => void;
  repository: SolutionsRepository;
  existingPackages?: readonly ExistingPackageRange[];
}

function isAdmitted(result: PackageLoadResult): boolean {
  return result.verified || (result.devMode && import.meta.env.DEV);
}

export function LoadPackageDialog({
  open,
  onClose,
  onLoaded,
  repository,
  existingPackages = [],
}: LoadPackageDialogProps) {
  const theme = useTheme();
  const inputRef = useRef<HTMLInputElement>(null);
  const selectionSequence = useRef(0);
  const [file, setFile] = useState<File | null>(null);
  const [validation, setValidation] = useState<PackageValidationResult | null>(null);
  const [validating, setValidating] = useState(false);

  const verification = useMutation({
    mutationFn: (selected: File) => repository.load(selected),
  });

  function reset() {
    selectionSequence.current += 1;
    setFile(null);
    setValidation(null);
    setValidating(false);
    verification.reset();
    if (inputRef.current !== null) inputRef.current.value = '';
  }

  // Every path that abandons a selection — Cancel, the dialog's own close,
  // and "Choose another" — drops whatever the repository was holding for
  // it. A file the operator walked away from must leave nothing behind.
  function afterPending(
    pending: void | Promise<void>,
    onComplete: () => void,
    onFailure?: () => void,
  ): void {
    if (pending !== undefined && typeof (pending as Promise<void>).then === 'function') {
      void pending.then(onComplete, onFailure);
      return;
    }
    onComplete();
  }

  function handleClose() {
    afterPending(repository.discardPending?.(), () => {
      reset();
      onClose();
    }, onClose);
  }

  function discardSelection() {
    afterPending(repository.discardPending?.(), reset);
  }

  function pick(selected: File | null | undefined) {
    if (selected === null || selected === undefined) {
      return;
    }
    afterPending(repository.discardPending?.(), () => beginPick(selected));
  }

  async function beginPick(selected: File) {
    verification.reset();
    const sequence = selectionSequence.current + 1;
    selectionSequence.current = sequence;
    setFile(selected);
    setValidation(null);
    setValidating(true);

    const inspected = await inspectPackageFile(selected, {
      devMode: import.meta.env.DEV,
      existingPackages,
    });
    if (selectionSequence.current !== sequence) return;
    setValidation(inspected);
    setValidating(false);
    if (inspected.valid) verification.mutate(selected);
  }

  const result = verification.data;
  const admitted = validation?.valid === true && result !== undefined && isAdmitted(result);

  function handleLoad() {
    if (result === undefined || !admitted) {
      return;
    }
    afterPending(repository.commitPending?.(), () => {
      onLoaded(result);
      reset();
    });
  }

  return (
    <Dialog
      open={open}
      title={t('solutionsLoadDialogTitle')}
      onClose={handleClose}
      actions={
        <>
          <Button variant="text" onClick={handleClose}>
            {t('cancel')}
          </Button>
          <Button disabled={!admitted || verification.isPending} onClick={handleLoad}>
            {verification.isPending ? t('solutionsLoadSubmitting') : t('solutionsLoadSubmit')}
          </Button>
        </>
      }
    >
      <input
        ref={inputRef}
        type="file"
        accept=".json,application/json"
        style={{ display: 'none' }}
        onChange={(event) => {
          void pick(event.target.files?.[0]);
        }}
      />

      {file === null ? (
        <Box
          data-dropzone
          role="button"
          tabIndex={0}
          aria-label={t('solutionsLoadDropzoneLabel')}
          onClick={() => inputRef.current?.click()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              inputRef.current?.click();
            }
          }}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            void pick(event.dataTransfer.files[0]);
          }}
          sx={{
            border: `2px dashed ${theme.palette.divider}`,
            borderRadius: 2,
            p: 4,
            textAlign: 'center',
            cursor: 'pointer',
          }}
        >
          <Box
            aria-hidden
            sx={{
              width: 52,
              height: 52,
              mx: 'auto',
              mb: 1.5,
              borderRadius: 1.5,
              display: 'grid',
              placeItems: 'center',
              backgroundColor: 'primary.main',
              color: 'primary.contrastText',
            }}
          >
            <UploadIcon fontSize="medium" />
          </Box>
          <Typography sx={{ fontWeight: 600 }}>{t('solutionsLoadDropzoneLabel')}</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
            {t('solutionsLoadDropzoneHint')}
          </Typography>
        </Box>
      ) : (
        <Box
          data-verification-result
          sx={{
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 2,
            p: 3,
            textAlign: 'center',
          }}
        >
          <Typography sx={{ fontWeight: 600, fontFamily: 'monospace', overflowWrap: 'anywhere' }}>
            {file.name}
          </Typography>

          {validating && (
            <Box
              sx={{
                mt: 1.5,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 1,
              }}
            >
              <CircularProgress size={16} />
              <Typography variant="caption" color="text.secondary">
                {t('solutionsLoadValidating')}
              </Typography>
            </Box>
          )}

          {validation !== null && (
            <Box sx={{ mt: 2, textAlign: 'left' }}>
              <PackageValidationSummary result={validation} />
            </Box>
          )}

          {validation?.valid === true && verification.isPending && (
            <Box
              sx={{
                mt: 1.5,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 1,
              }}
            >
              <CircularProgress size={16} />
              <Typography variant="caption" color="text.secondary">
                {t('solutionsLoadVerifying')}
              </Typography>
            </Box>
          )}

          {validation?.valid === true && verification.isError && (
            <Box sx={{ mt: 1.5, textAlign: 'left' }}>
              <Alert severity="err" title={t('solutionsLoadNetworkError')}>
                {verification.error instanceof ApiError
                  ? verification.error.message
                  : t('errorGeneric')}
              </Alert>
            </Box>
          )}

          {validation?.valid === true && result !== undefined && (
            <Box sx={{ mt: 1.5 }}>
              {result.verified ? (
                <Box
                  sx={{
                    display: 'flex',
                    gap: 0.75,
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Chip
                    tone="ok"
                    icon={<ToneIcon tone="ok" fontSize="small" />}
                    label={t('solutionsLoadVerifiedSigned')}
                  />
                  {result.signatureFingerprint !== undefined && (
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      sx={{ fontFamily: 'monospace' }}
                    >
                      {t('solutionsLoadVerifiedFingerprint', {
                        fingerprint: result.signatureFingerprint,
                      })}
                    </Typography>
                  )}
                </Box>
              ) : admitted ? (
                <Box>
                  <Chip
                    tone="warn"
                    icon={<ToneIcon tone="warn" fontSize="small" />}
                    label={t('solutionsLoadDevModeAdmitted')}
                  />
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ mt: 1, display: 'block', textAlign: 'left' }}
                  >
                    {t('solutionsLoadDevModeAdmittedDetail')}
                  </Typography>
                </Box>
              ) : (
                <Box>
                  <Chip
                    tone="err"
                    icon={<ToneIcon tone="err" fontSize="small" />}
                    label={t('solutionsLoadRejected')}
                  />
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ mt: 1, display: 'block', textAlign: 'left' }}
                  >
                    {t('solutionsLoadRejectedDetail')}
                  </Typography>
                </Box>
              )}
            </Box>
          )}

          <Box sx={{ mt: 1.5 }}>
            <Button variant="text" size="sm" onClick={discardSelection}>
              {t('solutionsLoadChooseAnother')}
            </Button>
          </Box>
        </Box>
      )}

      <Box sx={{ mt: 2 }}>
        <Alert severity="info">{t('solutionsLoadInfoBanner')}</Alert>
      </Box>

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1.5, display: 'block' }}>
        {t('solutionsLoadDialogAdminOnlyNote')}
      </Typography>
    </Dialog>
  );
}
