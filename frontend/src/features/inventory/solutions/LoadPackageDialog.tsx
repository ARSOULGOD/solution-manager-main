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
 * The current HTTP seam sends file METADATA only after local validation —
 * see `solutionsRepository.ts`'s `load()` header for why. Browser preflight
 * improves feedback but is never the trust boundary; the future byte-upload
 * backend must repeat every gate.
 *
 * **The confirm/dismiss edges are announced to the repository.** An
 * implementation that holds per-selection state between `load()` and the
 * operator's answer needs to know which way the answer went; the dev
 * package loader (FE-114) parses the dropped file's bytes locally and must
 * not put the result in the catalog until Load is clicked. Both hooks are
 * OPTIONAL and both are absent from the real repository, which holds
 * nothing between calls — hence `?.()` rather than a required method.
 */
import { Box, CircularProgress, Typography, useTheme } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import type {
  CanonicalSolutionPackage,
  ExistingPackageRange,
  PackageValidationResult,
} from '@contracts/solution-package';
import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Chip, Dialog, PackageGlyphIcon, ToneIcon } from '@shared/ui';

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
    mutationFn: (pkg: CanonicalSolutionPackage) => repository.load(pkg),
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
  function discard() {
    repository.discardPending?.();
    reset();
  }

  function handleClose() {
    discard();
    onClose();
  }

  async function pick(selected: File | null | undefined) {
    if (selected === null || selected === undefined) {
      return;
    }
    repository.discardPending?.();
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
    if (inspected.valid) verification.mutate(inspected.package);
  }

  const result = verification.data;
  // `loadPackage` (`console.yaml`) has no server-side signature-verification step — v1
  // loads no signed packages (`Solution.signature` is always `unsigned`). A successful
  // response IS the admission: there is nothing further to check (see file header and
  // `solutionsRepository.ts`'s header for why the pre-005 `verified`/`devMode` gate is
  // gone).
  const admitted = validation?.valid === true && result !== undefined;

  function handleLoad() {
    if (result === undefined || !admitted) {
      return;
    }
    repository.commitPending?.();
    onLoaded(result);
    reset();
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
          {(() => {
            // A purely presentational read of state already on screen (validating /
            // invalid / admitted / server error) — not a new field, same distinction
            // the chip and alert below already render, just given a tile+icon instead
            // of starting the card with plain text.
            const invalid = validation !== null && !validation.valid;
            const errored = validation?.valid === true && verification.isError;
            const admitted = validation?.valid === true && result !== undefined;
            const resolved = invalid || errored || admitted;
            const tone = invalid || errored ? 'err' : admitted ? 'ok' : 'idle';
            const { main, bg } = theme.palette.tone[tone];
            return (
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
                  backgroundColor: bg,
                  color: main,
                }}
              >
                {resolved ? <ToneIcon tone={tone} fontSize="medium" /> : <PackageGlyphIcon fontSize="medium" />}
              </Box>
            );
          })()}

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
            // `loadPackage` has no server-side verified/dev-mode distinction to render
            // (v1 loads no signed packages — see the file header and
            // `solutionsRepository.ts`'s header). A response at all IS the admission.
            <Box sx={{ mt: 1.5 }}>
              <Chip
                tone="ok"
                icon={<ToneIcon tone="ok" fontSize="small" />}
                label={t('solutionsLoadAdmitted')}
              />
            </Box>
          )}

          <Box sx={{ mt: 1.5 }}>
            <Button variant="text" size="sm" onClick={discard}>
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
