/**
 * Forced credential rotation (backend `Identity.mustRotate` / 428
 * `MUST_ROTATE`). Blocking by design — rendered by `RequireAuth` in place
 * of the normal app shell whenever `identity.mustRotate` is true, because
 * the backend itself fails every other authenticated call closed until
 * rotation succeeds.
 *
 * Lives under `shared/auth/` rather than `features/auth/` — `RequireAuth`
 * (this same directory) is the only caller, and `shared/` may not import
 * from `features/` (`eslint-plugin-boundaries`, `frontend/eslint.config.js`
 * — a `shared -> feature` edge has no `allow` policy and is proved
 * disallowed by `src/test/lint-fixtures/shared/imports-feature.ts`).
 *
 * "No dismiss path": `@shared/ui`'s `Dialog` has no prop to suppress its
 * close affordances (verified against `Dialog.tsx`/`Dialog.types.ts` —
 * there is no `disableClose`; `dirty` only blocks a backdrop click, not
 * Escape or the title-bar close button). Passing a no-op `onClose` here is
 * the real mechanism: MUI's `Dialog` (and this wrapper's own close button)
 * route every dismiss attempt through `onClose`, so making it inert is
 * what actually removes every dismiss path, not a prop this component
 * doesn't have.
 */
import { useState, type FormEvent } from 'react';

import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Dialog, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

export interface RotateCredentialDialogProps {
  open: boolean;
  onRotate: (current: string, next: string) => Promise<void>;
}

export function RotateCredentialDialog({ open, onRotate }: RotateCredentialDialogProps) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    try {
      await onRotate(current, next);
    } catch (submitError) {
      setError(submitError instanceof ApiError ? submitError.message : t('rotateCredentialUnreachable'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} title={t('rotateCredentialTitle')} onClose={() => {}}>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <Stack gap={2.25}>
          <Alert severity="info" fullWidth>
            {t('rotateCredentialExplanation')}
          </Alert>
          {error !== undefined && (
            <Alert severity="err" fullWidth>
              {error}
            </Alert>
          )}
          <TextField
            label={t('currentCredentialLabel')}
            required
            reveal
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
            autoComplete="current-password"
          />
          <TextField
            label={t('newCredentialLabel')}
            required
            reveal
            value={next}
            onChange={(event) => setNext(event.target.value)}
            autoComplete="new-password"
          />
          <Button type="submit" size="lg" block disabled={submitting}>
            {submitting ? t('rotatingCredential') : t('rotateCredentialSubmit')}
          </Button>
        </Stack>
      </form>
    </Dialog>
  );
}
