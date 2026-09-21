/**
 * The sign-in form (task 037, `D13` 🔒 deferred, `FE-D24`, UI §A.3). Four
 * states: `idle · submitting · rejected · unreachable`. `rejected` never
 * says which field was wrong — an account-enumeration leak (design §15) —
 * so it renders as one message under the password field, and the
 * username field is marked invalid too (no message) so both fields read
 * as errored together.
 *
 * Duplicate submission is blocked two ways: the submit button disables
 * while `submitting`, and `submittingRef` rejects a second `handleSubmit`
 * call that lands before the state update commits (e.g. a fast double
 * Enter).
 */
import { useRef, useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { ApiError } from '@shared/api';
import { useAuth } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { Alert, Button, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

type LoginStatus = 'idle' | 'submitting' | 'rejected' | 'setup-invalid' | 'unreachable';

// Feature code cannot import `@app/routes` (boundaries: feature -> shared/
// contracts only) — matches `SidebarNav`/`Breadcrumbs` (task 024), which
// hardcode the same literal for the same reason.
const HOME_PATH = '/';

export interface LoginFormProps {
  localBootstrap?: boolean;
}

export function LoginForm({ localBootstrap = false }: LoginFormProps) {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<LoginStatus>('idle');
  const submittingRef = useRef(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submittingRef.current) {
      return;
    }
    submittingRef.current = true;
    setStatus('submitting');

    if (localBootstrap && password.length < 12) {
      setStatus('setup-invalid');
      submittingRef.current = false;
      return;
    }

    try {
      await login({ username, password });
      const from = searchParams.get('from');
      await navigate(from !== null && from.length > 0 ? from : HOME_PATH, { replace: true });
    } catch (error) {
      setStatus(error instanceof ApiError ? 'rejected' : 'unreachable');
    } finally {
      submittingRef.current = false;
    }
  }

  const rejected = status === 'rejected';
  const setupInvalid = status === 'setup-invalid';
  const submitting = status === 'submitting';

  return (
    <form onSubmit={(event) => void handleSubmit(event)}>
      <Stack gap={2.25}>
        {status === 'unreachable' && (
          <Alert severity="err" title={t('loginUnreachableTitle')} fullWidth>
            {t('loginUnreachableBody')}
          </Alert>
        )}

        <TextField
          label={t('usernameLabel')}
          required
          value={username}
          onChange={(event) => {
            setUsername(event.target.value);
          }}
          autoComplete="username"
          autoFocus
          {...(rejected ? { error: ' ' } : {})}
        />
        <TextField
          label={t('passwordLabel')}
          required
          reveal
          value={password}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
          autoComplete="current-password"
          {...(rejected
            ? { error: t('loginRejected') }
            : setupInvalid
              ? { error: t('loginLocalDemoPasswordLength') }
              : {})}
        />
        <Button type="submit" size="lg" block disabled={submitting}>
          {submitting ? t('signingIn') : t('signIn')}
        </Button>
        {/* A single "Sign in with your organisation" SSO button drops in here,
            below a divider, without redesign — the slot is empty in v1 (`D13` deferred). */}
      </Stack>
    </form>
  );
}
